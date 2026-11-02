package fr.claudegateway.jarvis.pieces;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Ce que Jarvis dit d'une pièce</b> (F-237 / SF-237-01, cadrage §11) — pur. Uniquement des descriptions <b>déjà en
 * base</b> ; jamais une description d'image fabriquée (Provider-First).
 */
public final class PieceCaptions {

    /** Plafond d'une description. */
    public static final int MAX_CAPTION = 600;
    /** Plafond d'une description de dépôt (phrase ou message). */
    public static final int MAX_DEPOSIT = 300;
    /** Plafond d'un prompt d'image. */
    public static final int MAX_PROMPT = 200;
    /** Plafond d'un titre. */
    public static final int MAX_TITLE = 120;

    /** Les marqueurs que le terminal insère pour une pièce collée : {@code [Image #2]}, {@code [Vidéo #1]}… */
    static final Pattern MARKER = Pattern.compile(
            "\\[(?:Image|Vid[ée]o|Audio|PDF|Fichier|Document)[^\\]]{0,20}#\\d+\\]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private PieceCaptions() {
    }

    /** Une description et sa source. */
    public record Caption(String text, Piece.CaptionSource source) {
    }

    /**
     * La description d'un dépôt : la phrase qui contient son marqueur (le {@code position}-ième du message, base 0),
     * sinon le message entier, sinon {@code null} (le titre — nom et date — suffit).
     */
    public static Caption deposit(String message, int position) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher m = MARKER.matcher(message);
        int seen = 0;
        while (m.find()) {
            if (seen++ == position) {
                String sentence = sentenceAround(message, m.start(), m.end());
                if (!sentence.isBlank()) {
                    return new Caption(clip(sentence, MAX_DEPOSIT), Piece.CaptionSource.MESSAGE);
                }
            }
        }
        return new Caption(clip(message, MAX_DEPOSIT), Piece.CaptionSource.MESSAGE);
    }

    /** La phrase (délimitée par . ! ? ou fin de ligne) qui contient {@code [start, end)}. */
    static String sentenceAround(String text, int start, int end) {
        int from = start;
        while (from > 0 && !isBoundary(text.charAt(from - 1))) {
            from--;
        }
        int to = end;
        while (to < text.length() && !isBoundary(text.charAt(to))) {
            to++;
        }
        if (to < text.length() && text.charAt(to) != '\n') {
            to++; // garder la ponctuation finale
        }
        return text.substring(from, to).strip();
    }

    private static boolean isBoundary(char c) {
        return c == '.' || c == '!' || c == '?' || c == '\n';
    }

    /** La description d'une capture de preuve : titre, étape prouvée, bilan des commandes (jamais leur sortie). */
    public static Caption proof(String title, String step, List<String> statuses, List<Integer> exitCodes) {
        StringBuilder text = new StringBuilder(title == null || title.isBlank() ? "Preuve" : title.strip());
        if (step != null && !step.isBlank()) {
            text.append(" — étape : ").append(step.strip());
        }
        int total = statuses.size();
        if (total > 0) {
            int failed = 0;
            for (int i = 0; i < total; i++) {
                Integer code = i < exitCodes.size() ? exitCodes.get(i) : null;
                String status = statuses.get(i);
                if ((code != null && code != 0) || (status != null && !"done".equalsIgnoreCase(status))) {
                    failed++;
                }
            }
            text.append(" — ").append(total).append(total == 1 ? " commande" : " commandes");
            text.append(failed == 0 ? (total == 1 ? ", réussie" : ", toutes réussies") : ", " + failed + " en échec");
        }
        return new Caption(clip(text.toString(), MAX_CAPTION), Piece.CaptionSource.STEP);
    }

    /** Objet, destinataire et extrait — envois marqués et courriels. */
    public static Caption sent(String subject, String recipient, String excerpt) {
        StringBuilder text = new StringBuilder();
        if (subject != null && !subject.isBlank()) {
            text.append("« ").append(subject.strip()).append(" »");
        }
        if (recipient != null && !recipient.isBlank()) {
            text.append(text.isEmpty() ? "" : " ").append("à ").append(recipient.strip());
        }
        if (excerpt != null && !excerpt.isBlank()) {
            text.append(text.isEmpty() ? "" : " — ").append(excerpt.strip().replaceAll("\\s+", " "));
        }
        return text.isEmpty() ? null
                : new Caption(clip(text.toString(), MAX_CAPTION), Piece.CaptionSource.DESCRIPTION);
    }

    /** La première source non vide parmi les candidates, dans l'ordre. */
    public static Caption first(Caption... candidates) {
        for (Caption c : candidates) {
            if (c != null && c.text() != null && !c.text().isBlank()) {
                return new Caption(clip(c.text(), MAX_CAPTION), c.source());
            }
        }
        return null;
    }

    /** Une description candidate (vide ⇒ ignorée par {@link #first}). */
    public static Caption of(String text, Piece.CaptionSource source) {
        return text == null || text.isBlank() ? null : new Caption(text, source);
    }

    /** Coupe proprement à {@code max} caractères (points de suspension). */
    public static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        return v.length() <= max ? v : v.substring(0, Math.max(0, max - 1)).stripTrailing() + "…";
    }
}
