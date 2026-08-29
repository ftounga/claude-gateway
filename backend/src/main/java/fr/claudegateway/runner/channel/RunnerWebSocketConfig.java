package fr.claudegateway.runner.channel;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RunnerWebSocketConfig.class);

    /** Tampon de trame texte imposé par le contrat de messages §5 : 1 Mio des deux côtés. */
    static final int MAX_MESSAGE_BYTES = 1_048_576;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/runner/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }

    /**
     * Fixe la taille des trames acceptées par le conteneur (F-38 / SF-38-05, contrat §5).
     *
     * <p>Sans ce bean, le défaut du conteneur est de <b>8 192 octets</b> : la première lecture de
     * fichier un peu grosse dépasserait la limite et la socket serait coupée avec une erreur peu
     * lisible, loin de la cause. La borne applicative (contenu d'un {@code tool_result} ≤ 512 Kio)
     * reste très en deçà.</p>
     */
    @Bean
    public ServletServerContainerFactoryBean runnerWebSocketContainer() {
        ServletServerContainerFactoryBean container = new OptionalServerContainer();
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_MESSAGE_BYTES);
        return container;
    }

    /**
     * Variante tolérante d'un contexte <b>sans conteneur WebSocket</b>. Le fabricant standard exige
     * l'attribut {@code jakarta.websocket.server.ServerContainer} du {@code ServletContext} et échoue
     * sinon — ce qui est le cas de tout test {@code @SpringBootTest} en environnement MOCK, où aucun
     * serveur n'est démarré. Faire tomber le contexte de test entier pour un réglage de tampon serait
     * un très mauvais échange : ici on applique les bornes quand un conteneur existe, et on ne fait
     * rien quand il n'y en a pas.
     */
    static class OptionalServerContainer extends ServletServerContainerFactoryBean {

        @Override
        public void afterPropertiesSet() {
            try {
                super.afterPropertiesSet();
            } catch (IllegalStateException ex) {
                log.debug("Aucun conteneur WebSocket dans ce contexte : bornes de trame non appliquées");
            }
        }
    }
}
