package fr.claudegateway.jarvis.cards;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * <b>Le coupe-circuit des pancartes</b> (F-248 / SF-248-01, D12 ; porte K7) — {@code APP_JARVIS_CARDS_ENABLED},
 * <b>activé</b> par défaut : faux ⇒ aucun événement {@code cards}, aucune fiche calculée, aucune lecture
 * {@code /api/jarvis/cards}. Revenir en arrière sans déployer.
 *
 * <p>Un seul constructeur (record compact) : un second casserait le démarrage du contexte Spring.</p>
 *
 * @param enabled tout le dispositif des pancartes
 */
@ConfigurationProperties(prefix = "app.jarvis.cards")
public record JarvisCardsProperties(Boolean enabled) {

    public JarvisCardsProperties {
        enabled = !Boolean.FALSE.equals(enabled);
    }

    /** Activé (tests). */
    public static JarvisCardsProperties defaults() {
        return new JarvisCardsProperties(null);
    }

    public boolean on() {
        return enabled;
    }

    /** Active la configuration des pancartes. */
    @Configuration
    @EnableConfigurationProperties(JarvisCardsProperties.class)
    static class Config {
    }
}
