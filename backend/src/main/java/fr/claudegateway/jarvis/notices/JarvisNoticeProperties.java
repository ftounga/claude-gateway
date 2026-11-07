package fr.claudegateway.jarvis.notices;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>Les annonces d'interface</b> (F-249) — réglages. Un seul constructeur (record compact).
 *
 * @param enabled coupe-circuit ({@code APP_JARVIS_NOTICES_ENABLED}, D19) : aucune annonce créée, liste vide
 */
@ConfigurationProperties(prefix = "app.jarvis.notices")
public record JarvisNoticeProperties(Boolean enabled) {

    public JarvisNoticeProperties {
        enabled = enabled == null || enabled;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
