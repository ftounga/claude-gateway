package fr.claudegateway.byok;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Sélectionne l'implémentation {@link ByokKeyCipher} selon la configuration (aucun secret journalisé) :
 * <ol>
 *   <li>{@code app.byok.kms-key-id} défini → {@link KmsEnvelopeCipher} (prod/staging) ;</li>
 *   <li>sinon {@code app.byok.local-key} défini → {@link LocalAesByokKeyCipher} (dev/tests) ;</li>
 *   <li>sinon → {@link DisabledByokKeyCipher} (dormant, l'ajout de clé renverra 503).</li>
 * </ol>
 * Le choix « dormant » garantit que l'absence de configuration KMS n'empêche jamais le démarrage.
 */
@Configuration
@EnableConfigurationProperties(ByokProperties.class)
public class ByokConfig {

    private static final Logger log = LoggerFactory.getLogger(ByokConfig.class);

    @Bean
    public ByokKeyCipher byokKeyCipher(ByokProperties properties) {
        if (properties.kmsConfigured()) {
            log.info("BYOK : chiffrement via AWS KMS (région {}).", properties.region());
            KmsClient kmsClient = KmsClient.builder()
                    .region(Region.of(properties.region()))
                    .build();
            return new KmsEnvelopeCipher(kmsClient, properties.kmsKeyId());
        }
        if (properties.localConfigured()) {
            log.warn("BYOK : chiffrement LOCAL (clé de test) — à réserver au dev/tests, jamais en production.");
            return new LocalAesByokKeyCipher(properties.localKey());
        }
        log.info("BYOK : dormant (aucun chiffrement configuré) — l'ajout de clé renverra 503.");
        return new DisabledByokKeyCipher();
    }
}
