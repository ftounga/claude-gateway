package fr.claudegateway.runner.relay;

import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

/**
 * Ajoute le <b>second connecteur TCP</b> qui sert les routes {@code /internal/**}
 * (F-38 / SF-38-12).
 *
 * <p>Pourquoi un second connecteur et pas un simple chemin : {@code application.yml} fixe
 * {@code server.servlet.context-path: /api} et l'ingress route {@code /api} en {@code Prefix} vers le
 * Service {@code claude-gateway-backend:8080}. Une route interne publiée sur 8080 s'appellerait donc
 * {@code /api/internal/...} et serait joignable depuis Internet, avec pour seule protection un secret
 * partagé devant de l'exécution de commandes chez l'utilisateur. Le port 8081 n'est publié que par le
 * Service headless {@code claude-gateway-backend-internal}, qui n'apparaît dans aucun Ingress.</p>
 *
 * <p>Le connecteur partage le même contexte servlet, donc le même {@code DispatcherServlet} et le
 * même context-path : les URL internes sont bien {@code /api/internal/...}, mais sur le port
 * 8081 seulement. Aucune compression n'est activée : elle bufferiserait la réponse et casserait le
 * flux NDJSON au fil de l'eau.</p>
 */
public class RunnerRelayConnectorCustomizer
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private static final Logger log = LoggerFactory.getLogger(RunnerRelayConnectorCustomizer.class);

    private final int configuredPort;
    private volatile Connector connector;

    public RunnerRelayConnectorCustomizer(int configuredPort) {
        this.configuredPort = configuredPort;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        Connector relayConnector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        relayConnector.setPort(configuredPort);
        relayConnector.setThrowOnFailure(true);
        // Compression explicitement désactivée (défaut Tomcat) : le flux NDJSON doit partir ligne à
        // ligne, sans être retenu dans un tampon de compression.
        relayConnector.setProperty("compression", "off");
        factory.addAdditionalTomcatConnectors(relayConnector);
        this.connector = relayConnector;
        log.info("Connecteur de relais interne configuré sur le port {}", configuredPort);
    }

    /**
     * Port réellement écouté. Différent du port configuré quand celui-ci vaut {@code 0} (tests :
     * Tomcat choisit un port libre) — c'est ce port-là que le filtre doit comparer, sinon toute
     * requête serait vue comme venant du port public.
     */
    public int relayPort() {
        Connector current = connector;
        int bound = current == null ? -1 : current.getLocalPort();
        return bound > 0 ? bound : configuredPort;
    }
}
