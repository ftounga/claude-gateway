package fr.claudegateway.jarvis.clientmail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>Les mails Outlook des clients</b> (F-249) — réglages. Un seul constructeur (record compact) : un second casserait
 * le démarrage du contexte Spring.
 *
 * @param enabled  coupe-circuit de la route de dépôt et des travaux ({@code APP_JARVIS_CLIENT_MAIL_ENABLED}, D19)
 * @param maxBytes taille maximale d'un fichier déposé (défaut 20 Mo, D3)
 */
@ConfigurationProperties(prefix = "app.jarvis.client-mail")
public record ClientMailProperties(Boolean enabled, Integer maxBytes) {

    public static final int DEFAULT_MAX_BYTES = 20 * 1024 * 1024;

    public ClientMailProperties {
        enabled = enabled == null || enabled;
        maxBytes = maxBytes == null || maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
