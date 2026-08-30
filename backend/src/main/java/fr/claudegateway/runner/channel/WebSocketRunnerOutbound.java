package fr.claudegateway.runner.channel;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Canal d'émission adossé à une {@code WebSocketSession} (F-38 / SF-38-09, transport historique de
 * SF-38-02/05). La session portée ici est <b>déjà décorée</b> par un
 * {@code ConcurrentWebSocketSessionDecorator} : la session Spring n'est pas thread-safe et toutes
 * les émissions — {@code heartbeat_ack} compris — doivent traverser la même instance (contrat §7).
 */
public final class WebSocketRunnerOutbound implements RunnerOutbound {

    private static final Logger log = LoggerFactory.getLogger(WebSocketRunnerOutbound.class);

    private final WebSocketSession session;

    public WebSocketRunnerOutbound(WebSocketSession session) {
        this.session = session;
    }

    /** Session décorée sous-jacente, pour les écritures directes du gestionnaire WebSocket. */
    public WebSocketSession session() {
        return session;
    }

    @Override
    public void send(String frame) throws IOException {
        session.sendMessage(new TextMessage(frame));
    }

    @Override
    public boolean isOpen() {
        return session.isOpen();
    }

    @Override
    public void close() {
        try {
            session.close(CloseStatus.NORMAL);
        } catch (IOException | RuntimeException ex) {
            log.debug("Fermeture de la socket runner impossible : déjà indisponible");
        }
    }
}
