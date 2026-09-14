package fr.claudegateway.mail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.docx.DocxTextExtractor;
import fr.claudegateway.docx.InvalidDocxException;

/**
 * <b>Un secret dans le texte d'un document joint</b> (F-110 / SF-110-05).
 *
 * <p>SF-110-03 refusait un secret dans une pièce <b>texte</b> et un fichier <b>nommé</b> comme un conteneur de
 * secrets, mais laissait passer un secret caché dans le <b>contenu</b> d'un document binaire. Cette classe lit le
 * texte d'un {@code .docx}, d'un {@code .xlsx} ou d'un PDF — <b>uniquement</b> pour y chercher, au moment de
 * l'envoi, ce que {@link ClientMailSecrets} reconnaît comme manifestement un mot de passe, un jeton ou une clé.</p>
 *
 * <h2>Ce n'est pas un « moteur documentaire »</h2>
 *
 * <p>Aucune indexation, aucun Q&amp;A, aucun appel modèle : ce serait le pipeline documentaire (F-05→08),
 * asynchrone. Ici, une garde de sécurité <b>locale et bornée</b>, dans l'esprit du refus déjà en place pour les
 * pièces texte. Le texte extrait n'est jamais conservé ni journalisé.</p>
 *
 * <h2>Effort au mieux, échec en s'ouvrant</h2>
 *
 * <p>Un document illisible, corrompu, chiffré, scanné (image sans couche texte) ou dépassant une borne anti
 * zip-bomb n'est <b>pas</b> inspecté : il part comme un binaire ordinaire (comme avant SF-110-05). Bloquer un
 * document qu'on ne sait pas lire serait un faux positif ; l'inspection est une défense en profondeur, pas une
 * garantie — la consigne de l'outil et le contrôle du nom restent les premières lignes.</p>
 *
 * <h2>Sans bibliothèque</h2>
 *
 * <p>Même doctrine que {@link DocxTextExtractor} : un {@code .docx}/{@code .xlsx} est un zip de XML, deux formats
 * que le JDK lit nativement ; un PDF se décompresse avec l'{@link Inflater} du JDK. Les garde-fous (bornes
 * zip-bomb, refus des entités XML externes, inflate borné) sont <b>écrits ici</b>, relisibles en review, plutôt
 * que cachés dans les réglages d'une dépendance.</p>
 */
@Component
public class DocumentSecretScanner {

    private static final Logger log = LoggerFactory.getLogger(DocumentSecretScanner.class);

    /** Signature d'une entrée locale de zip : « PK\003\004 ». */
    private static final byte[] ZIP_MAGIC = { 0x50, 0x4B, 0x03, 0x04 };
    /** Signature d'un PDF, cherchée dans les premiers octets (certains outils posent des octets avant). */
    private static final byte[] PDF_MAGIC = { '%', 'P', 'D', 'F', '-' };
    private static final int PDF_MAGIC_WINDOW = 1024;

    /** La partie qui signe un classeur Excel. */
    private static final String XLSX_WORKBOOK = "xl/workbook.xml";
    private static final String XLSX_SHARED_STRINGS = "xl/sharedstrings.xml";
    private static final String XLSX_WORKSHEETS = "xl/worksheets/";

    // Bornes anti zip-bomb pour le .xlsx (le .docx a les siennes dans DocxProperties). L'entrée est déjà bornée
    // à 10 Mo en amont (plafond d'un courriel), mais un classeur légitime, très compressible, dépasse couramment
    // dix fois sa taille compressée : on lit large, en s'arrêtant net à la borne.
    private static final int MAX_ZIP_ENTRIES = 4096;
    private static final long MAX_ZIP_ENTRY_BYTES = 32L * 1024 * 1024;
    private static final long MAX_ZIP_TOTAL_BYTES = 64L * 1024 * 1024;

    // Bornes du PDF : nombre de flux inspectés, octets décompressés au total, et longueur du texte retenu.
    private static final int MAX_PDF_STREAMS = 4096;
    private static final long MAX_PDF_DECODED_BYTES = 64L * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 5_000_000;

    private final DocxTextExtractor docx;

    public DocumentSecretScanner(DocxTextExtractor docx) {
        this.docx = docx;
    }

    /**
     * La nature du premier secret manifeste trouvé dans le <b>texte</b> d'un document joint.
     *
     * @param content octets de la pièce (jamais {@code null} attendu, mais toléré)
     * @return par exemple « un mot de passe », ou vide si le contenu n'est pas un document inspectable, s'il est
     *         illisible, ou s'il ne porte aucun secret manifeste
     */
    public Optional<String> secretIn(byte[] content) {
        String text = extractText(content);
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        return ClientMailSecrets.find(text);
    }

    /** Le texte d'un document reconnu, ou {@code null} : rien à inspecter, ou illisible (fail-open). */
    private String extractText(byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }
        try {
            if (startsWithPdf(content)) {
                return extractPdfText(content);
            }
            if (hasZipMagic(content)) {
                return extractOoxmlText(content);
            }
        } catch (RuntimeException ex) {
            // Illisible, corrompu, borne dépassée : non inspecté, joint comme un binaire ordinaire.
            log.debug("Pièce jointe non inspectée pour secrets (document illisible)", ex);
        }
        return null;
    }

    // ------------------------------------------------------------------ OOXML (.docx / .xlsx)

    private String extractOoxmlText(byte[] content) {
        if (docx.looksLikeDocx(content)) {
            try {
                return docx.extract(content).text();
            } catch (InvalidDocxException ex) {
                return null;
            }
        }
        return extractXlsxText(content);
    }

    /**
     * Le texte d'un {@code .xlsx} : les chaînes partagées ({@code xl/sharedStrings.xml}) et les chaînes en ligne
     * des feuilles ({@code xl/worksheets/*.xml}). Un mot de passe tapé dans une cellule vit dans l'un ou l'autre.
     * On ne s'intéresse qu'aux éléments {@code <t>} — porteurs de texte dans les deux parties.
     */
    private String extractXlsxText(byte[] content) {
        StringBuilder text = new StringBuilder();
        long total = 0;
        int entries = 0;
        boolean isWorkbook = false;
        byte[] buffer = new byte[8192];
        XMLInputFactory factory = hardenedFactory();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    return null;
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalize(entry.getName());
                boolean wanted = XLSX_WORKBOOK.equals(name) || XLSX_SHARED_STRINGS.equals(name)
                        || (name.startsWith(XLSX_WORKSHEETS) && name.endsWith(".xml"));
                ByteArrayOutputStream sink = wanted ? new ByteArrayOutputStream() : null;
                long entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > MAX_ZIP_ENTRY_BYTES || total > MAX_ZIP_TOTAL_BYTES) {
                        return null;
                    }
                    if (sink != null) {
                        sink.write(buffer, 0, read);
                    }
                }
                if (XLSX_WORKBOOK.equals(name)) {
                    isWorkbook = true;
                }
                if (sink != null && !XLSX_WORKBOOK.equals(name)) {
                    appendTextElements(factory, sink.toByteArray(), text);
                    if (text.length() >= MAX_TEXT_CHARS) {
                        break;
                    }
                }
            }
        } catch (Exception ex) {
            // ZipException, XML illisible, DTD refusé : non inspecté.
            log.debug("Classeur .xlsx non inspecté pour secrets", ex);
            return null;
        }
        // Une archive OOXML sans workbook n'est pas un classeur (un .docx serait déjà parti plus haut) : rien.
        return isWorkbook ? text.toString() : null;
    }

    /** Ajoute le texte de tous les éléments {@code <t>} d'une partie XML (chaînes partagées ou en ligne). */
    private void appendTextElements(XMLInputFactory factory, byte[] xml, StringBuilder out) {
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml), StandardCharsets.UTF_8.name());
            while (reader.hasNext() && out.length() < MAX_TEXT_CHARS) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && "t".equals(reader.getLocalName())) {
                    out.append(reader.getElementText()).append('\n');
                }
            }
        } catch (XMLStreamException ex) {
            // Une partie illisible ne condamne pas les autres : on l'ignore.
            log.debug("Partie XML d'un .xlsx illisible", ex);
        } finally {
            closeQuietly(reader);
        }
    }

    // ------------------------------------------------------------------ PDF

    /**
     * Le texte d'un PDF, <b>au mieux</b> : les chaînes montrées par les opérateurs de texte ({@code Tj}/{@code TJ})
     * dans les flux de contenu, littérales {@code ( )} et hexadécimales {@code < >}. Les flux {@code FlateDecode}
     * sont décompressés ; un flux non encodé n'est lu que s'il est majoritairement textuel (éviter d'extraire du
     * bruit d'une image). Un PDF chiffré, scanné (image sans texte) ou en police CID/Type0 ne rend rien
     * d'exploitable — limite assumée.
     */
    private String extractPdfText(byte[] content) {
        StringBuilder text = new StringBuilder();
        long decoded = 0;
        int streams = 0;
        int from = 0;
        while (text.length() < MAX_TEXT_CHARS && decoded < MAX_PDF_DECODED_BYTES && streams < MAX_PDF_STREAMS) {
            int keyword = indexOf(content, STREAM, from);
            if (keyword < 0) {
                break;
            }
            int dataStart = afterStreamKeyword(content, keyword);
            int dataEnd = indexOf(content, ENDSTREAM, dataStart);
            if (dataEnd < 0) {
                break;
            }
            streams++;
            boolean flate = mentionsFlate(content, keyword);
            byte[] body = pdfStreamBody(content, dataStart, dataEnd, flate);
            from = dataEnd + ENDSTREAM.length;
            if (body == null || body.length == 0) {
                continue;
            }
            decoded += body.length;
            if (!flate && !looksTextual(body)) {
                // Flux binaire non encodé (image, police) : on n'y cherche pas de texte.
                continue;
            }
            extractPdfStrings(body, text);
        }
        return text.length() == 0 ? null : text.toString();
    }

    private static final byte[] STREAM = "stream".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ENDSTREAM = "endstream".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FLATE = "FlateDecode".getBytes(StandardCharsets.US_ASCII);

    /** Début des données après le mot-clé {@code stream}, qui est suivi de CRLF ou LF (spec PDF §7.3.8). */
    private static int afterStreamKeyword(byte[] c, int keyword) {
        int i = keyword + STREAM.length;
        if (i < c.length && c[i] == '\r') {
            i++;
        }
        if (i < c.length && c[i] == '\n') {
            i++;
        }
        return i;
    }

    /** Le dictionnaire du flux mentionne-t-il {@code FlateDecode} ? Cherché dans les octets qui précèdent. */
    private static boolean mentionsFlate(byte[] c, int keyword) {
        int lookback = Math.max(0, keyword - 2048);
        int found = indexOf(c, FLATE, lookback);
        return found >= 0 && found < keyword;
    }

    private byte[] pdfStreamBody(byte[] c, int start, int end, boolean flate) {
        int len = end - start;
        if (len <= 0) {
            return null;
        }
        // Le flux peut finir par un EOL avant « endstream » : on ne le retire pas, il ne gêne pas l'extraction.
        if (!flate) {
            byte[] raw = new byte[len];
            System.arraycopy(c, start, raw, 0, len);
            return raw;
        }
        byte[] inflated = inflate(c, start, len, false);
        if (inflated == null) {
            inflated = inflate(c, start, len, true);
        }
        return inflated;
    }

    /** Décompresse un flux zlib (ou deflate brut si {@code nowrap}) sous borne ; {@code null} en cas d'échec. */
    private byte[] inflate(byte[] data, int offset, int length, boolean nowrap) {
        Inflater inflater = new Inflater(nowrap);
        inflater.setInput(data, offset, length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                } else {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_PDF_DECODED_BYTES) {
                        break;
                    }
                }
            }
        } catch (DataFormatException ex) {
            return out.size() > 0 ? out.toByteArray() : null;
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /**
     * Extrait les chaînes des flux de contenu : littérales {@code (…)} (échappements et parenthèses équilibrées) et
     * hexadécimales {@code <…>} (en écartant les dictionnaires {@code <<…>>}). Ce sont les opérandes des opérateurs
     * de texte ; un mot de passe montré à l'écran y figure en clair pour une police standard.
     */
    private void extractPdfStrings(byte[] body, StringBuilder out) {
        int i = 0;
        int n = body.length;
        while (i < n && out.length() < MAX_TEXT_CHARS) {
            int b = body[i] & 0xFF;
            if (b == '(') {
                i = readLiteralString(body, i + 1, out);
            } else if (b == '<' && i + 1 < n && (body[i + 1] & 0xFF) != '<') {
                i = readHexString(body, i + 1, out);
            } else {
                i++;
            }
        }
    }

    /** Lit une chaîne littérale à partir de {@code i} (après le {@code (}) ; rend l'index après le {@code )}. */
    private int readLiteralString(byte[] c, int i, StringBuilder out) {
        int depth = 1;
        int n = c.length;
        StringBuilder s = new StringBuilder();
        while (i < n) {
            int b = c[i] & 0xFF;
            if (b == '\\') {
                i++;
                if (i >= n) {
                    break;
                }
                int e = c[i] & 0xFF;
                switch (e) {
                    case 'n' -> s.append('\n');
                    case 'r' -> s.append('\r');
                    case 't' -> s.append('\t');
                    case 'b' -> s.append('\b');
                    case 'f' -> s.append('\f');
                    case '(' -> s.append('(');
                    case ')' -> s.append(')');
                    case '\\' -> s.append('\\');
                    case '\r', '\n' -> { /* saut de ligne échappé : effacé */ }
                    default -> {
                        if (e >= '0' && e <= '7') {
                            int value = e - '0';
                            for (int k = 0; k < 2 && i + 1 < n && c[i + 1] >= '0' && c[i + 1] <= '7'; k++) {
                                i++;
                                value = value * 8 + (c[i] - '0');
                            }
                            s.append((char) (value & 0xFF));
                        } else {
                            s.append((char) e);
                        }
                    }
                }
                i++;
            } else if (b == '(') {
                depth++;
                s.append('(');
                i++;
            } else if (b == ')') {
                depth--;
                i++;
                if (depth == 0) {
                    break;
                }
                s.append(')');
            } else {
                s.append((char) b);
                i++;
            }
        }
        appendString(out, s.toString());
        return i;
    }

    /** Lit une chaîne hexadécimale à partir de {@code i} (après le {@code <}) ; rend l'index après le {@code >}. */
    private int readHexString(byte[] c, int i, StringBuilder out) {
        int n = c.length;
        StringBuilder hex = new StringBuilder();
        while (i < n && (c[i] & 0xFF) != '>') {
            int b = c[i] & 0xFF;
            if (isHex(b)) {
                hex.append((char) b);
            }
            i++;
        }
        if (i < n) {
            i++; // le '>'
        }
        StringBuilder s = new StringBuilder();
        for (int k = 0; k + 1 < hex.length(); k += 2) {
            s.append((char) Integer.parseInt(hex.substring(k, k + 2), 16));
        }
        if (hex.length() % 2 == 1) {
            s.append((char) Integer.parseInt(hex.substring(hex.length() - 1) + "0", 16));
        }
        appendString(out, s.toString());
        return i;
    }

    private static void appendString(StringBuilder out, String s) {
        if (!s.isEmpty()) {
            out.append(s).append(' ');
        }
    }

    private static boolean isHex(int b) {
        return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
    }

    /** Un flux non encodé est-il majoritairement du texte imprimable ? Sinon, c'est une image ou une police. */
    private static boolean looksTextual(byte[] body) {
        int sample = Math.min(body.length, 4096);
        if (sample == 0) {
            return false;
        }
        int printable = 0;
        for (int i = 0; i < sample; i++) {
            int b = body[i] & 0xFF;
            if (b == '\t' || b == '\n' || b == '\r' || (b >= 0x20 && b < 0x7F)) {
                printable++;
            }
        }
        return printable * 100L / sample >= 85;
    }

    // ------------------------------------------------------------------ outils communs

    private static boolean startsWithPdf(byte[] c) {
        return indexWithin(c, PDF_MAGIC, PDF_MAGIC_WINDOW) >= 0;
    }

    private static boolean hasZipMagic(byte[] c) {
        if (c.length < ZIP_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (c[i] != ZIP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        return name;
    }

    private static XMLInputFactory hardenedFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("Résolution d'entité externe refusée.");
        });
        return factory;
    }

    private static void closeQuietly(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException ex) {
            log.debug("Fermeture du lecteur XML en échec", ex);
        }
    }

    /** Index de {@code needle} dans {@code hay} à partir de {@code from}, ou -1. */
    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Index de {@code needle} dans les {@code window} premiers octets de {@code hay}, ou -1. */
    private static int indexWithin(byte[] hay, byte[] needle, int window) {
        int limit = Math.min(hay.length, window);
        for (int i = 0; i <= limit - needle.length; i++) {
            boolean hit = true;
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return i;
            }
        }
        return -1;
    }
}
