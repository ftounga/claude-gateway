package fr.claudegateway.runner;

import java.io.IOException;
import java.util.List;

/**
 * I/O du repli long-polling (F-38 / SF-38-09), isolée derrière une interface pour que la boucle de
 * repli ({@link PollingConnection}) soit testable sans réseau — exactement la raison d'être de
 * {@link FrameTransport} côté WebSocket.
 *
 * <p>Les trames échangées sont celles du contrat de messages, <b>telles quelles</b> : ce transport
 * ne définit aucun type nouveau, il les déplace dans un corps HTTP.</p>
 */
public interface PollingTransport {

    /**
     * Réclame les trames en attente ({@code POST /runner/poll}). Bloque au plus {@code waitMs} côté
     * gateway ; une liste vide est le fonctionnement normal, pas une erreur.
     *
     * @throws RunnerConnection.AuthRejectedException si le jeton est refusé (401)
     * @throws ChannelClosedException                 si la gateway a fermé la liaison (409)
     * @throws IOException                            en cas d'incident réseau (le runner réessaie)
     */
    List<String> poll(long waitMs) throws IOException;

    /** Dépose des trames sortantes ({@code POST /runner/send}). */
    void send(List<String> frames) throws IOException;

    /** Signale l'arrêt propre du runner ({@code POST /runner/disconnect}). Ne lève jamais. */
    void disconnect();

    /** La gateway a fermé la liaison (coupe-circuit, balayage d'inactivité) : le runner s'arrête. */
    class ChannelClosedException extends RuntimeException {
        public ChannelClosedException(String message) {
            super(message);
        }
    }
}
