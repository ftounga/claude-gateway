package fr.claudegateway.runner.channel;

/**
 * Codes d'erreur émis <b>par le backend</b> lors du routage d'un appel d'outil vers un runner
 * (F-38 / SF-38-05, contrat de messages §4). Ils ne sont jamais reçus du runner : ils décrivent ce
 * que la gateway constate elle-même (personne au bout du fil, mauvais nœud, silence, réponse
 * illisible).
 *
 * <p>Chaque code porte le message rendu <b>au modèle</b>, en français et sans détail sensible —
 * même discipline que le {@code catch RuntimeException} historique de la boucle tool-use : jamais de
 * trace technique, jamais de chemin de machine.</p>
 */
public final class RunnerErrorCodes {

    /** Aucun runner connecté pour ce workspace, ou socket fermée en vol. */
    public static final String RUNNER_UNAVAILABLE = "runner_unavailable";
    /** Runner présent (cross-replica) mais sa socket vit sur l'autre nœud — voir contrat §8. */
    public static final String RUNNER_NOT_ON_THIS_NODE = "runner_not_on_this_node";
    /** Réponse illisible ou non conforme au contrat. */
    public static final String RUNNER_PROTOCOL_ERROR = "runner_protocol_error";
    /** Rien reçu dans {@code timeoutMs + 5 000 ms}. */
    public static final String RUNNER_TIMEOUT = "runner_timeout";
    /** Paramètre d'appel refusé <b>avant</b> émission (borne de taille, argument manquant). */
    public static final String INVALID_INPUT = "invalid_input";
    /** Outil non annoncé par le runner (trame {@code ready}). */
    public static final String UNSUPPORTED_TOOL = "unsupported_tool";

    private RunnerErrorCodes() {
    }

    /** Message rendu au modèle pour un code backend ; repli neutre pour un code inattendu. */
    public static String messageFor(String code) {
        return switch (code) {
            case RUNNER_UNAVAILABLE -> "Aucun runner n'est connecté pour ce projet. "
                    + "Démarre le runner sur ta machine, puis réessaie.";
            case RUNNER_NOT_ON_THIS_NODE -> "Le runner n'est pas joignable depuis ce nœud.";
            case RUNNER_PROTOCOL_ERROR -> "Réponse illisible du runner.";
            case RUNNER_TIMEOUT -> "Le runner n'a pas répondu dans le délai imparti.";
            case UNSUPPORTED_TOOL -> "Cet outil n'est pas supporté par le runner connecté.";
            default -> "Opération refusée.";
        };
    }
}
