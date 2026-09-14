package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * <b>Le texte d'un {@code .docx} déjà sur la machine</b> (F-108 / SF-108-06) — la transcription Word.
 *
 * <h2>Relayer, pas analyser</h2>
 *
 * <p>Un {@code .docx} est une archive ZIP dont {@code word/document.xml} porte le texte. Cette classe
 * en <b>extrait le texte brut</b> — paragraphes, tabulations, sauts de ligne — et le rend tel quel :
 * elle n'indexe rien, ne fait ni OCR ni RAG (Provider-First, {@code PROJECT.md} §3.3) — l'analyse du
 * document est fournie par le modèle. Le fichier a été rapatrié par {@code teams_read_file} ou
 * {@code teams_meeting_recording} ; il ne quitte pas la machine, et rien de secret n'en sort : on ne
 * lit qu'un fichier local.</p>
 */
final class DocxText {

    /** Au-delà, le texte rendu est tronqué (et le résultat le dit). */
    static final int MAX_TEXT_CHARS = 500_000;

    /** Le résultat d'une extraction : le texte, le nombre de paragraphes, la troncature. */
    record Extracted(String text, int paragraphs, boolean truncated) {
    }

    private DocxText() {
    }

    /**
     * Lit le texte d'un {@code .docx}.
     *
     * @throws NotADocx si le fichier n'est pas un ZIP contenant {@code word/document.xml}
     */
    static Extracted read(Path file) throws NotADocx {
        String xml = documentXml(file);
        int paragraphs = countParagraphs(xml);
        String text = toText(xml);
        boolean truncated = text.length() > MAX_TEXT_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
        return new Extracted(text, paragraphs, truncated);
    }

    private static String documentXml(Path file) throws NotADocx {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) {
                throw new NotADocx("ce n'est pas un .docx lisible : l'archive ne contient pas "
                        + "word/document.xml");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new NotADocx("ce n'est pas un .docx lisible : "
                    + (e.getMessage() == null ? "archive illisible" : e.getMessage()));
        }
    }

    /** Le nombre de paragraphes Word ({@code <w:p>}). */
    private static int countParagraphs(String xml) {
        int count = 0;
        int from = 0;
        while (true) {
            int open = xml.indexOf("<w:p", from);
            if (open < 0) {
                break;
            }
            char next = open + 4 < xml.length() ? xml.charAt(open + 4) : ' ';
            // « <w:p> » ou « <w:p ... » : un paragraphe ; pas « <w:pPr », « <w:pStyle »…
            if (next == '>' || next == ' ' || next == '/') {
                count++;
            }
            from = open + 4;
        }
        return count;
    }

    /**
     * Le texte des passages Word. Les balises {@code </w:p>} deviennent des sauts de ligne, {@code
     * <w:tab/>} des tabulations, {@code <w:br/>} des sauts ; le reste des balises disparaît, et les
     * entités XML sont décodées.
     */
    private static String toText(String xml) {
        String marked = xml
                .replaceAll("(?i)<w:tab\\b[^>]*/?>", "\t")
                .replaceAll("(?i)<w:br\\b[^>]*/?>", "\n")
                .replaceAll("(?i)<w:cr\\b[^>]*/?>", "\n")
                .replaceAll("(?i)</w:p>", "\n");
        String stripped = marked.replaceAll("<[^>]*>", "");
        String decoded = unescape(stripped);
        // Nettoyage doux : jamais de retour chariot Windows, pas de fin de lignes chargées d'espaces.
        return decoded.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \t]+\n", "\n").strip();
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int semi = value.indexOf(';', i);
            if (semi < 0 || semi - i > 12) {
                out.append(c);
                i++;
                continue;
            }
            String entity = value.substring(i + 1, semi);
            String replacement = switch (entity) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                default -> numeric(entity);
            };
            if (replacement == null) {
                out.append(c);
                i++;
            } else {
                out.append(replacement);
                i = semi + 1;
            }
        }
        return out.toString();
    }

    /** Une entité numérique {@code &#123;} ou {@code &#x1F;}, ou {@code null} si elle n'en est pas une. */
    private static String numeric(String entity) {
        if (entity.isEmpty() || entity.charAt(0) != '#') {
            return null;
        }
        try {
            int code = entity.length() > 1 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')
                    ? Integer.parseInt(entity.substring(2), 16)
                    : Integer.parseInt(entity.substring(1));
            return new String(Character.toChars(code));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Le fichier n'est pas un {@code .docx} lisible : on le dit, on n'invente rien. */
    static final class NotADocx extends Exception {

        private static final long serialVersionUID = 1L;

        NotADocx(String message) {
            super(message);
        }
    }
}
