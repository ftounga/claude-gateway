package fr.claudegateway.runner;

/**
 * Résultat d'exécution d'un outil runner (F-38 / SF-38-04), avant sérialisation en trame
 * {@code tool_result}.
 *
 * @param ok        succès de l'appel
 * @param content   texte du résultat (jamais {@code null} si {@code ok}) ; ignoré sinon
 * @param truncated vrai si le producteur a coupé le contenu (contrat §5)
 * @param bytes     octets lus ou écrits, pour l'audit (SF-38-08) ; {@code -1} si non applicable
 * @param errorCode code de la liste close du contrat (§4) ; {@code null} si {@code ok}
 * @param errorMessage message français sans chemin absolu ; {@code null} si {@code ok}
 * @param exitCode  code de sortie du processus, <b>uniquement</b> pour {@code bash} (SF-38-07) ;
 *                  {@code null} pour tout autre outil (contrat §2.4)
 */
public record ToolOutcome(boolean ok, String content, boolean truncated, long bytes,
        String errorCode, String errorMessage, Integer exitCode) {

    public static ToolOutcome ok(String content, boolean truncated, long bytes) {
        return new ToolOutcome(true, content == null ? "" : content, truncated, bytes, null, null, null);
    }

    public static ToolOutcome ok(String content) {
        return ok(content, false, -1);
    }

    public static ToolOutcome error(String code, String message) {
        return new ToolOutcome(false, null, false, -1, code, message, null);
    }

    public static ToolOutcome error(ToolException exception) {
        return error(exception.code(), exception.getMessage());
    }
}
