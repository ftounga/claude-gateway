package fr.claudegateway.runner.channel;

import java.io.IOException;

/**
 * Canal d'émission vers le runner d'un workspace (F-38 / SF-38-09). Abstraction du <b>transport</b>,
 * introduite pour que le repli long-polling passe exactement par le même chemin que le WebSocket :
 * {@link RunnerCallDispatcher} émet des trames, il ne sait pas — et ne doit pas savoir — si elles
 * partent sur une socket ouverte ou attendent dans une file qu'un {@code POST /runner/poll} vienne
 * les chercher.
 *
 * <p>C'est ce qui garantit qu'un seul endroit applique les délais, l'annulation, le coupe-circuit et
 * l'audit, quel que soit le tuyau.</p>
 *
 * <p>Deux implémentations : {@link WebSocketRunnerOutbound} (SF-38-02/05) et
 * {@link LongPollingRunnerOutbound} (SF-38-09).</p>
 */
public interface RunnerOutbound {

    /**
     * Émet une trame du contrat de messages, telle quelle.
     *
     * @throws IOException si le canal ne peut plus émettre (socket fermée, file saturée) — l'appel
     *                     en cours devient alors {@code runner_unavailable}, sans rejeu
     */
    void send(String frame) throws IOException;

    /** Vrai tant que le canal peut porter une trame. */
    boolean isOpen();

    /** Ferme le canal. Idempotent : un second appel ne fait rien. */
    void close();
}
