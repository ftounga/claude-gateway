package fr.claudegateway.jarvis;

import java.util.regex.Pattern;

/**
 * <b>La phrase orale d'une réponse</b> (F-231 / SF-231-06, D3) : la ligne « 🎙 » que Jarvis dit en tête de réponse,
 * sinon la première ligne de prose — débarrassée du balisage (titres, gras, marqueurs de l'essentiel). Sert à
 * résumer un tour né à la voix dans l'encart « Pendant ton absence ».
 *
 * <p>Pur et déterministe : aucune dépendance.</p>
 */
public final class JarvisReplies {

    /** La marque de la phrase orale en tête de réponse (D3). */
    public static final String ORAL_MARK = "🎙";

    static final int MAX_LINE = 280;

    private static final Pattern TAGS = Pattern.compile("<<\\s*/?\\s*[a-zA-Zéè]+\\s*>>");
    private static final Pattern MARKUP = Pattern.compile("^[#>*\\-\\s]+|[*_`]+");

    private JarvisReplies() {
    }

    /** La phrase orale de {@code content}, ou {@code null} pour une réponse vide ou faite seulement de blocs. */
    public static String oralLine(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String fallback = null;
        boolean inFence = false;
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence || line.isEmpty()) {
                continue;
            }
            String clean = clean(line);
            if (clean.isEmpty()) {
                continue;
            }
            if (line.startsWith(ORAL_MARK) || clean.startsWith(ORAL_MARK)) {
                return cut(clean.substring(clean.indexOf(ORAL_MARK) + ORAL_MARK.length()).strip());
            }
            if (fallback == null) {
                fallback = clean;
            }
        }
        return fallback == null ? null : cut(fallback);
    }

    private static String clean(String line) {
        String noTags = TAGS.matcher(line).replaceAll("");
        return MARKUP.matcher(noTags).replaceAll("").strip();
    }

    private static String cut(String text) {
        return text.length() <= MAX_LINE ? text : text.substring(0, MAX_LINE - 1).stripTrailing() + "…";
    }
}
