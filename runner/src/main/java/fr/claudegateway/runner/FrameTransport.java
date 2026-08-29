package fr.claudegateway.runner;

import java.util.concurrent.CompletableFuture;

/**
 * Émission d'une trame texte sur le canal runner (F-38 / SF-38-04). Abstraction volontairement
 * minimale au-dessus de {@code java.net.http.WebSocket#sendText}, pour que la file d'émission
 * ({@link FrameSender}) et le dispatcher d'outils soient testables sans réseau.
 */
@FunctionalInterface
public interface FrameTransport {

    /** Émet une trame ; le futur se complète quand l'envoi est terminé. */
    CompletableFuture<?> send(String frame);
}
