package fr.claudegateway.runner;

/**
 * Contexte d'exécution d'un appel d'outil (F-38 / SF-38-07). Il porte ce qu'un outil ne peut pas
 * déduire de ses seuls arguments : par où <b>diffuser</b> sa sortie au fil de l'eau, combien de temps
 * il a le droit de tourner, et si l'appel a déjà été abandonné.
 *
 * <p>Introduit pour {@code bash} : les outils fichiers rendent un résultat d'un bloc, une commande
 * doit pouvoir montrer ses lignes pendant qu'elle tourne. Les autres outils ignorent simplement ce
 * contexte.</p>
 */
public interface ToolContext {

    /**
     * Diffuse un fragment de sortie (trame {@code tool_stream}, contrat §2.3).
     *
     * @param stream {@code "stdout"} ou {@code "stderr"}
     * @param chunk  texte déjà décodé, {@code <=} 16 384 octets
     */
    void stream(String stream, String chunk);

    /** Délai imparti à l'appel, en millisecondes (toujours {@code > 0}). */
    long timeoutMs();

    /** Vrai dès que l'appel est terminé côté aiguilleur (annulation, délai dépassé, socket perdue). */
    boolean cancelled();

    /** Contexte neutre : rien n'est diffusé, délai par défaut du contrat, jamais annulé. */
    static ToolContext none() {
        return new ToolContext() {
            @Override
            public void stream(String stream, String chunk) {
                // Aucun canal de diffusion : le résultat sera rendu d'un bloc.
            }

            @Override
            public long timeoutMs() {
                return ToolDispatcher.DEFAULT_TIMEOUT_MS;
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }
}
