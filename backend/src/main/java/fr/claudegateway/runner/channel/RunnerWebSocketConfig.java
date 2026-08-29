package fr.claudegateway.runner.channel;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Publie le canal WebSocket runner (F-38 / SF-38-02) sur {@code /runner/ws} (soit {@code /api/runner/ws}
 * avec le context-path). Le handshake est authentifié par {@link RunnerHandshakeInterceptor} (jeton
 * runner) ; la chaîne de sécurité dédiée {@code /runner/**} laisse passer la requête d'upgrade.
 *
 * <p>Origines autorisées : {@code *}. La sécurité ne repose pas sur l'origine mais sur le jeton runner
 * (le runner est un client non-navigateur, SF-38-03, sans cookie de session à protéger).</p>
 */
@Configuration
@EnableWebSocket
public class RunnerWebSocketConfig implements WebSocketConfigurer {

    private final RunnerWebSocketHandler handler;
    private final RunnerHandshakeInterceptor handshakeInterceptor;

    public RunnerWebSocketConfig(RunnerWebSocketHandler handler,
            RunnerHandshakeInterceptor handshakeInterceptor) {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/runner/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
