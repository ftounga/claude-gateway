package fr.claudegateway.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Câblage du Web Push (F-153 / SF-153-02).
 *
 * <p>Le {@link WebPushTransport} est <b>réel</b> uniquement si VAPID est configuré (clés fournies
 * par l'environnement) ; sinon c'est un {@link DisabledWebPushTransport} — repli propre, aucun octet
 * ne part. Si la construction du transport réel échoue (clés mal formées), on retombe sur le
 * transport inactif plutôt que d'empêcher le démarrage : l'émission push n'est jamais critique.</p>
 */
@Configuration
@EnableConfigurationProperties(PushProperties.class)
public class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    @Bean
    WebPushTransport webPushTransport(PushProperties properties) {
        if (!properties.isConfigured()) {
            log.info("Web Push non configuré (pas de clés VAPID) : émission inactive, "
                    + "le signal in-tab reste le repli.");
            return new DisabledWebPushTransport();
        }
        try {
            log.info("Web Push configuré : émission active.");
            return new MartijndwarsWebPushTransport(properties);
        } catch (Exception e) {
            log.warn("Clés VAPID présentes mais transport Web Push non initialisable ({}) : "
                    + "émission inactive.", e.getMessage());
            return new DisabledWebPushTransport();
        }
    }
}
