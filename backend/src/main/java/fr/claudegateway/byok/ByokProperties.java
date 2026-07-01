package fr.claudegateway.byok;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du chiffrement BYOK (F-03, OQ-06). Toutes les valeurs sont externalisées.
 *
 * @param kmsKeyId alias/ARN/id de la clé KMS (env {@code APP_BYOK_KMS_KEY_ID}) — vide => pas de KMS
 * @param region   région AWS de la clé KMS (défaut {@code eu-west-3})
 * @param localKey clé maître LOCALE base64 (dev/tests uniquement) — jamais en production
 */
@ConfigurationProperties(prefix = "app.byok")
public record ByokProperties(String kmsKeyId, String region, String localKey) {

    public ByokProperties {
        if (region == null || region.isBlank()) {
            region = "eu-west-3";
        }
    }

    /** Vrai si une clé KMS est configurée (chiffrement réel via AWS KMS). */
    public boolean kmsConfigured() {
        return kmsKeyId != null && !kmsKeyId.isBlank();
    }

    /** Vrai si une clé maître locale est configurée (dev/tests). */
    public boolean localConfigured() {
        return localKey != null && !localKey.isBlank();
    }
}
