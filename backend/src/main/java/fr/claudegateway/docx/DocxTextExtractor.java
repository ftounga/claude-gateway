package fr.claudegateway.docx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lit le texte d'un document Word {@code .docx} (F-86 / SF-86-01).
 *
 * <p><b>Pourquoi cette classe n'est pas un {@code OcrProvider}.</b> Les deux gestes de cette
 * interface — {@code extractSync} et {@code startAsync} — décrivent une <i>reconnaissance de
 * caractères sur une image</i>. Un {@code .docx} n'est pas une image : il n'y a rien à reconnaître,
 * seulement à lire. L'y faire passer obligerait à mentir sur ce que fait l'interface, ou à glisser
 * un cas particulier dans un fournisseur dont le métier est tout autre. L'extraction est donc
 * <b>synchrone</b>, et pour une meilleure raison que les images : elle ne sort pas de la machine.
 *
 * <p><b>Aucune bibliothèque.</b> Un {@code .docx} est un zip contenant du XML, deux formats que le
 * JDK lit nativement. Apache POI (~12 Mo avec {@code xmlbeans}) apporterait le modèle objet de
 * trois formats bureautiques pour atteindre un fichier XML — et surtout déplacerait les deux
 * garde-fous qui comptent (bornes zip-bomb, refus des entités XML) dans des réglages internes.
 * Ici ils sont <b>écrits</b>, et tiennent en quelques lignes relisibles en review.
 *
 * <h2>Ce qui est lu, ce qui ne l'est pas</h2>
 * <ul>
 *   <li>{@code word/document.xml} et {@code word/footnotes.xml} : <b>retenus</b>. Les notes de bas
 *       de page portent souvent le sens d'un document juridique ou contractuel.</li>
 *   <li>{@code word/header*.xml}, {@code word/footer*.xml} : <b>jamais retenus</b>. Un en-tête
 *       répété à chaque page pollue le contexte sans rien apprendre. Ce n'est pas un filtrage a
 *       posteriori : ces parties ne sont pas analysées, donc ne peuvent pas fuir.</li>
 *   <li>Images : <b>comptées, jamais lues</b>. Les passer par l'OCR rendrait l'extraction
 *       asynchrone pour tout le monde au profit d'un cas rare. Le texte rendu <b>annonce</b> leur
 *       nombre, pour qu'un document amputé ne passe pas pour un document complet.</li>
 *   <li>Tableaux : rendus en <b>Markdown</b>. Le texte extrait part chez le fournisseur dans le
 *       contexte d'un tour ; un tableau mis à plat perd la relation ligne/colonne, justement ce
 *       qu'on interroge dans un document professionnel.</li>
 * </ul>
 *
 * <h2>Sécurité</h2>
 * <ul>
 *   <li><b>Zip-bomb</b> : nombre d'entrées, octets décompressés par entrée et octets décompressés
 *       au total sont bornés ({@link DocxProperties}), sur les octets <b>réellement lus</b> — pas
 *       sur {@code ZipEntry.getSize()}, que l'archive déclare et peut donc mentir. La lecture est
 *       interrompue <b>à</b> la borne : les octets suivants ne sont jamais décompressés.</li>
 *   <li><b>Entités XML externes (XXE)</b> : le DTD est refusé ({@code SUPPORT_DTD=false}), ce qui
 *       ferme d'un coup les entités externes, les entités récursives et les entités de paramètre ;
 *       un {@code XMLResolver} qui lève est posé en plus, comme ceinture. Un {@code .docx}
 *       malveillant ne peut ni lire un fichier du pod ni ouvrir une connexion sortante.</li>
 *   <li><b>Fichier renommé</b> : le nom n'est jamais regardé. Le refus porte sur le contenu —
 *       signature d'archive, puis présence réelle de {@code word/document.xml}.</li>
 * </ul>
 *
 * <p>Aucune trace technique ne sort d'ici : {@link InvalidDocxException} porte une phrase destinée
 * à l'utilisateur, la cause reste pour le journal.
 */
@Component
public class DocxTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocxTextExtractor.class);

    /** La partie qui porte le corps du document. Son absence signe un fichier qui n'est pas un .docx. */
    static final String DOCUMENT_PART = "word/document.xml";

    /** Les notes de bas de page — gardées, contrairement aux en-têtes et pieds de page. */
    static final String FOOTNOTES_PART = "word/footnotes.xml";

    /** Signature d'une entrée locale de zip : « PK\003\004 ». Premier verrou, sur le contenu. */
    private static final byte[] ZIP_MAGIC = { 0x50, 0x4B, 0x03, 0x04 };

    private static final String TOO_BIG = "Ce document Word est trop volumineux une fois décompressé.";
    private static final String CORRUPT_ARCHIVE =
            "Ce document Word est illisible : le fichier est corrompu ou incomplet.";
    private static final String CORRUPT_XML =
            "Ce document Word est illisible : son contenu interne est corrompu.";
    private static final String NOT_AN_ARCHIVE =
            "Ce fichier n'est pas un document Word (.docx) : son contenu n'est pas une archive Office.";
    private static final String NOT_A_DOCX = "Ce fichier n'est pas un document Word (.docx) valide.";

    private final DocxProperties properties;

    public DocxTextExtractor(DocxProperties properties) {
        this.properties = properties;
    }

    /**
     * Vrai si ce contenu <b>est</b> un document Word — décidé sur le contenu seul, jamais sur un
     * nom de fichier. Ne lève jamais : c'est une question, pas une validation.
     */
    public boolean looksLikeDocx(byte[] content) {
        if (!hasZipMagic(content)) {
            return false;
        }
        try {
            return readParts(content).containsKey(DOCUMENT_PART);
        } catch (InvalidDocxException ex) {
            return false;
        }
    }

    /**
     * Lit le texte du document.
     *
     * @param content octets du {@code .docx}
     * @return le texte et le nombre d'images non lues
     * @throws InvalidDocxException si le contenu n'est pas un {@code .docx} lisible, ou s'il
     *                              dépasse un garde-fou (message destiné à l'utilisateur, sans
     *                              aucune trace technique)
     */
    public DocxExtraction extract(byte[] content) {
        if (content == null || content.length == 0) {
            throw new InvalidDocxException("Ce fichier est vide.");
        }
        if (!hasZipMagic(content)) {
            throw new InvalidDocxException(NOT_AN_ARCHIVE);
        }
        Map<String, byte[]> parts = readParts(content);
        byte[] document = parts.get(DOCUMENT_PART);
        if (document == null) {
            // Une archive valide, mais pas un document Word : un .zip renommé tombe ici.
            throw new InvalidDocxException(NOT_A_DOCX);
        }

        XMLInputFactory factory = hardenedFactory();
        Context context = new Context();

        // Les notes d'abord : le corps doit pouvoir poser « [1] » à l'endroit exact où la note est
        // appelée. Une note détachée de sa place perd la moitié de ce qu'elle apporte — dans un
        // contrat, savoir QUELLE clause la note nuance est le sens même de la note.
        byte[] footnotesPart = parts.get(FOOTNOTES_PART);
        if (footnotesPart != null) {
            parse(factory, footnotesPart, reader -> readFootnotes(reader, context));
        }

        StringBuilder body = new StringBuilder();
        parse(factory, document, reader -> readBody(reader, body, context));

        return new DocxExtraction(
                assemble(body.toString(), context.footnotes, context.images), context.images);
    }

    /**
     * L'état d'une extraction : ce qu'on compte et ce qu'on a déjà lu. Un objet plutôt qu'une
     * enfilade de paramètres à travers huit méthodes récursives.
     */
    private static final class Context {

        /** Images rencontrées, jamais lues. */
        private int images;

        /** Notes de bas de page, dans l'ordre du document, déjà numérotées. */
        private final List<String> footnotes = new ArrayList<>();

        /**
         * Identifiant Word d'une note → son numéro de lecture. Word numérote en interne à partir
         * de valeurs qui lui appartiennent ({@code -1}, {@code 0} pour ses séparateurs techniques,
         * puis n'importe quoi) : rendre cet identifiant à l'utilisateur afficherait « [2] » pour la
         * première note du document. On renumérote donc de 1 à n, dans l'ordre.
         */
        private final Map<String, Integer> footnoteNumbers = new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------ archive

    private static boolean hasZipMagic(byte[] content) {
        if (content == null || content.length < ZIP_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (content[i] != ZIP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parcourt l'archive <b>une fois</b> et ne retient que les deux parties utiles, en appliquant
     * les trois bornes anti zip-bomb sur les octets réellement décompressés. Les entrées non
     * retenues sont traversées (il faut bien avancer dans le flux) mais jamais conservées — et
     * leurs octets comptent dans le total, sans quoi la bombe se cacherait dans une entrée qu'on
     * ne garde pas.
     */
    private Map<String, byte[]> readParts(byte[] content) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > properties.maxEntries()) {
                    throw new InvalidDocxException(TOO_BIG);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeEntryName(entry.getName());
                boolean wanted = DOCUMENT_PART.equals(name) || FOOTNOTES_PART.equals(name);
                ByteArrayOutputStream sink = wanted ? new ByteArrayOutputStream() : null;
                long entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > properties.maxEntryBytes() || total > properties.maxTotalBytes()) {
                        // Interrompu ICI : le reste de l'entrée n'est jamais décompressé.
                        throw new InvalidDocxException(TOO_BIG);
                    }
                    if (sink != null) {
                        sink.write(buffer, 0, read);
                    }
                }
                if (sink != null) {
                    parts.put(name, sink.toByteArray());
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            // ZipException, flux tronqué, en-tête illisible : rien de tout cela ne remonte au
            // client. Le détail va au journal de debug, la phrase va à l'utilisateur.
            log.debug("Archive .docx illisible", ex);
            throw new InvalidDocxException(CORRUPT_ARCHIVE, ex);
        }
        return parts;
    }

    private static String normalizeEntryName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/').toLowerCase(Locale.ROOT);
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        return name;
    }

    // ------------------------------------------------------------------ XML

    /**
     * Fabrique un lecteur XML <b>fermé</b>. {@code SUPPORT_DTD=false} suffit à refuser les entités
     * externes (XXE), les entités récursives (« milliard de rires ») et les entités de paramètre :
     * sans DTD, il n'y a pas d'entité à déclarer. Les deux autres réglages sont posés
     * explicitement — on n'hérite pas d'un défaut de bibliothèque, on l'écrit.
     */
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

    /** Ce qu'un lecteur de partie sait faire, pour partager l'ouverture et la fermeture du flux. */
    @FunctionalInterface
    private interface PartReader {
        void read(XMLStreamReader reader) throws XMLStreamException;
    }

    private void parse(XMLInputFactory factory, byte[] xml, PartReader partReader) {
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml), StandardCharsets.UTF_8.name());
            partReader.read(reader);
        } catch (XMLStreamException ex) {
            // Y compris le refus de DTD : un .docx porteur d'une entité externe finit ici, sans
            // qu'aucun fichier ait été ouvert ni aucune connexion tentée.
            log.debug("Contenu XML d'un .docx illisible", ex);
            throw new InvalidDocxException(CORRUPT_XML, ex);
        } finally {
            closeQuietly(reader);
        }
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

    // ------------------------------------------------------------------ corps du document

    private void readBody(XMLStreamReader reader, StringBuilder out, Context context)
            throws XMLStreamException {
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "p" -> appendParagraph(out, readParagraph(reader, context));
                    case "tbl" -> appendTable(out, readTable(reader, context));
                    default -> {
                        // Tout le reste (styles, propriétés de section, marque-pages…) : ignoré.
                    }
                }
            }
        }
    }

    /** Le texte d'un paragraphe, tabulations et sauts de ligne compris ; images comptées. */
    private String readParagraph(XMLStreamReader reader, Context context) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "t" -> text.append(reader.getElementText());
                    case "tab" -> text.append('\t');
                    case "br", "cr" -> text.append('\n');
                    // L'appel de note, posé à sa place dans la phrase — pas l'identifiant interne
                    // de Word, mais le numéro de lecture attribué en lisant word/footnotes.xml.
                    case "footnoteReference" -> {
                        Integer number = context.footnoteNumbers.get(attribute(reader, "id"));
                        if (number != null) {
                            text.append('[').append(number).append(']');
                        }
                    }
                    // Une image : comptée, son sous-arbre écarté sans être lu.
                    case "drawing", "pict" -> {
                        context.images++;
                        skipSubtree(reader);
                    }
                    default -> {
                        // `w:delText` (texte supprimé en suivi de modifications) tombe ici : un
                        // texte que l'auteur a retiré n'a pas à revenir dans le contexte.
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "p".equals(reader.getLocalName())) {
                break;
            }
        }
        return text.toString();
    }

    private static void skipSubtree(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    // ------------------------------------------------------------------ tableaux

    private List<List<String>> readTable(XMLStreamReader reader, Context context) throws XMLStreamException {
        List<List<String>> rows = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT && "tr".equals(reader.getLocalName())) {
                rows.add(readRow(reader, context));
            } else if (event == XMLStreamConstants.END_ELEMENT && "tbl".equals(reader.getLocalName())) {
                break;
            }
        }
        return rows;
    }

    private List<String> readRow(XMLStreamReader reader, Context context) throws XMLStreamException {
        List<String> cells = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT && "tc".equals(reader.getLocalName())) {
                cells.add(readCell(reader, context));
            } else if (event == XMLStreamConstants.END_ELEMENT && "tr".equals(reader.getLocalName())) {
                break;
            }
        }
        return cells;
    }

    /**
     * Une cellule tient sur une ligne : c'est la contrainte du Markdown. Ses paragraphes sont
     * joints par une espace, et un tableau imbriqué est aplati <b>dans</b> sa cellule plutôt que
     * perdu — un tableau dans un tableau reste rare, mais le perdre en silence serait un mensonge.
     */
    private String readCell(XMLStreamReader reader, Context context) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if ("p".equals(reader.getLocalName())) {
                    appendWithSpace(text, readParagraph(reader, context));
                } else if ("tbl".equals(reader.getLocalName())) {
                    appendWithSpace(text, flatten(readTable(reader, context)));
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "tc".equals(reader.getLocalName())) {
                break;
            }
        }
        return text.toString();
    }

    private static void appendWithSpace(StringBuilder target, String addition) {
        if (addition.isBlank()) {
            return;
        }
        if (target.length() > 0) {
            target.append(' ');
        }
        target.append(addition);
    }

    /** Un tableau imbriqué, réduit à une ligne : cellules séparées par « / », rangées par « ; ». */
    private static String flatten(List<List<String>> rows) {
        List<String> flattenedRows = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            flattenedRows.add(String.join(" / ", row));
        }
        return String.join(" ; ", flattenedRows);
    }

    private static void appendTable(StringBuilder out, List<List<String>> rows) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) {
            return;
        }
        ensureBlankLine(out);
        out.append(renderRow(rows.get(0), columns));
        out.append("|");
        out.append(" --- |".repeat(columns));
        out.append('\n');
        for (int i = 1; i < rows.size(); i++) {
            out.append(renderRow(rows.get(i), columns));
        }
        out.append('\n');
    }

    private static String renderRow(List<String> cells, int columns) {
        StringBuilder line = new StringBuilder("|");
        for (int i = 0; i < columns; i++) {
            line.append(' ')
                    .append(escapeCell(i < cells.size() ? cells.get(i) : ""))
                    .append(" |");
        }
        return line.append('\n').toString();
    }

    /** Une cellule ne peut ni couper la ligne ni ouvrir une colonne : « | » est échappé. */
    private static String escapeCell(String cell) {
        return cell.replace("|", "\\|").replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ notes de bas de page

    private void readFootnotes(XMLStreamReader reader, Context context) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event != XMLStreamConstants.START_ELEMENT || !"footnote".equals(reader.getLocalName())) {
                continue;
            }
            String type = attribute(reader, "type");
            if ("separator".equals(type) || "continuationSeparator".equals(type)) {
                // Les deux notes techniques que Word met en tête du fichier : aucun contenu utile.
                skipSubtree(reader);
                continue;
            }
            String id = attribute(reader, "id");
            StringBuilder body = new StringBuilder();
            while (reader.hasNext()) {
                int inner = reader.next();
                if (inner == XMLStreamConstants.START_ELEMENT && "p".equals(reader.getLocalName())) {
                    appendWithSpace(body, readParagraph(reader, context));
                } else if (inner == XMLStreamConstants.END_ELEMENT
                        && "footnote".equals(reader.getLocalName())) {
                    break;
                }
            }
            String text = body.toString().replaceAll("\\s+", " ").trim();
            if (!text.isEmpty()) {
                int number = context.footnotes.size() + 1;
                context.footnotes.add("[" + number + "] " + text);
                if (id != null) {
                    context.footnoteNumbers.put(id, number);
                }
            }
        }
    }

    /** Valeur d'un attribut par son nom local : les attributs OOXML portent le préfixe {@code w:}. */
    private static String attribute(XMLStreamReader reader, String localName) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (localName.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ assemblage

    private static void appendParagraph(StringBuilder out, String paragraph) {
        if (paragraph.isBlank()) {
            ensureBlankLine(out);
            return;
        }
        out.append(paragraph).append('\n');
    }

    /** Une ligne vide, et une seule : un document Word est plein de paragraphes vides. */
    private static void ensureBlankLine(StringBuilder out) {
        if (out.length() == 0) {
            return;
        }
        if (out.length() >= 2 && out.charAt(out.length() - 1) == '\n' && out.charAt(out.length() - 2) == '\n') {
            return;
        }
        if (out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        out.append('\n');
    }

    /**
     * Le texte final : l'annonce des images <b>en tête</b>, le corps, puis les notes de bas de page.
     *
     * <p>L'annonce est en tête et non en fin parce que ce texte part dans le contexte d'un tour :
     * un modèle doit lire la réserve avant le contenu, pas après l'avoir résumé.
     */
    private static String assemble(String body, List<String> footnotes, int images) {
        StringBuilder out = new StringBuilder();
        if (images > 0) {
            out.append(images == 1
                            ? "[1 image de ce document n'a pas été lue.]"
                            : "[" + images + " images de ce document n'ont pas été lues.]")
                    .append("\n\n");
        }
        out.append(body.stripTrailing());
        if (!footnotes.isEmpty()) {
            out.append("\n\nNotes de bas de page\n");
            for (String footnote : footnotes) {
                out.append(footnote).append('\n');
            }
        }
        return out.toString().stripTrailing();
    }
}
