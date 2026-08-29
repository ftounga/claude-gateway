package fr.claudegateway.runner.channel;

/**
 * Issue d'un appel d'outil routé vers un runner (F-38 / SF-38-05, contrat de messages §2.4).
 * Volontairement <b>neutre vis-à-vis du transport</b> : que le {@code tool_result} soit arrivé par
 * WebSocket (SF-38-02) ou, plus tard, par le repli long-polling (SF-38-09), l'appelant voit la même
 * structure.
 *
 * @param ok           issue de l'appel
 * @param content      contenu renvoyé par le runner ({@code ""} possible), vide si {@code !ok}
 * @param truncated    vrai si le producteur a coupé le contenu
 * @param exitCode     code de sortie, uniquement pour {@code bash} (SF-38-07), {@code null} ailleurs
 * @param durationMs   durée mesurée par le producteur, en millisecondes
 * @param bytes        octets lus/écrits, {@code null} si non renseigné (alimente l'audit SF-38-08)
 * @param errorCode    code de la liste close du contrat §4, {@code null} si {@code ok}
 * @param errorMessage message en français, sans chemin absolu ni détail sensible
 * @param streamed     sortie accumulée depuis les trames {@code tool_stream} (vide hors {@code bash})
 * @param streamTruncated vrai si l'agrégat de flux a été coupé à sa borne
 */
public record RunnerCallResult(
        boolean ok,
        String content,
        boolean truncated,
        Integer exitCode,
        long durationMs,
        Long bytes,
        String errorCode,
        String errorMessage,
        String streamed,
        boolean streamTruncated) {

    /** Erreur produite par le backend lui-même (contrat §4, seconde moitié). */
    public static RunnerCallResult backendError(String code) {
        return backendError(code, RunnerErrorCodes.messageFor(code));
    }

    /** Erreur produite par le backend avec un message explicite (borne dépassée, argument manquant). */
    public static RunnerCallResult backendError(String code, String message) {
        return new RunnerCallResult(false, "", false, null, 0L, null, code, message, "", false);
    }
}
