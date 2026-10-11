package fr.claudegateway.atelier.dto;

/**
 * <b>La passation du fil</b> (F-117 / SF-117-09) : le résumé de compaction tel qu'il sera réinjecté au
 * prochain tour, pour que l'utilisateur puisse le lire et le corriger.
 *
 * @param summary le résumé (Markdown, gabarit en cinq rubriques), ou {@code null} si le fil n'en a pas
 * @param length  sa longueur en caractères (0 sans résumé)
 */
public record ThreadHandoffResponse(String summary, int length) {

    public static ThreadHandoffResponse of(String summary) {
        if (summary == null || summary.isBlank()) {
            return new ThreadHandoffResponse(null, 0);
        }
        return new ThreadHandoffResponse(summary, summary.length());
    }
}
