package fr.claudegateway.atelier.routine;

/**
 * <b>La ligne de compte rendu d'une exécution</b> (F-183 / SF-183-04, D4) : « Routine du matin — 3
 * attentes mises à jour ». Calcul pur.
 *
 * <p>Le compte rendu, c'est le tour lui-même, dans le fil ; cette ligne n'en est que l'en-tête — aucun
 * second appel au modèle pour « résumer » (Gateway-First, et la justesse ne se délègue pas à un résumé).</p>
 */
public final class RoutineReport {

    /** Longueur maximale de la ligne (colonne {@code summary}). */
    public static final int MAX = 300;

    private RoutineReport() {
    }

    /**
     * La ligne d'une exécution.
     *
     * @param name            nom de la routine
     * @param status          issue de l'exécution
     * @param attentesUpdated attentes créées ou modifiées pendant l'exécution ; {@code null} = inconnu
     */
    public static String summary(String name, RoutineRunStatus status, Integer attentesUpdated) {
        String head = (name == null || name.isBlank() ? "Routine" : name.strip()) + " — ";
        String body = switch (status) {
            case MISSED_OFFLINE -> "manquée — poste hors ligne";
            case SKIPPED_BUDGET -> "non lancée — budget du mois atteint";
            case RUNNING -> "en cours";
            default -> attentes(attentesUpdated) + suffix(status);
        };
        String line = head + body;
        return line.length() <= MAX ? line : line.substring(0, MAX - 1) + "…";
    }

    private static String attentes(Integer count) {
        if (count == null) {
            return "terminée";
        }
        if (count == 0) {
            return "terminée, aucune attente modifiée";
        }
        return count == 1 ? "1 attente mise à jour" : count + " attentes mises à jour";
    }

    private static String suffix(RoutineRunStatus status) {
        return switch (status) {
            case INCOMPLETE -> " · INCOMPLET (budget atteint)";
            case FAILED -> " · échec";
            case INTERRUPTED -> " · interrompue";
            default -> "";
        };
    }
}
