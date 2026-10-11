package fr.claudegateway.atelier.plume;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de <b>Plume</b> (F-193 / SF-193-01) : coupe-circuit {@code APP_ATELIER_ENRICH_ENABLED}
 * (vrai par défaut) et modèle {@code APP_ATELIER_ENRICH_MODEL} (vide ⇒ le modèle de la boucle).
 *
 * <p>Un seul constructeur (compact) : un second casserait le démarrage du contexte Spring.</p>
 *
 * @param enabled faux ⇒ aucun appel au fournisseur, la route répond 503
 * @param model   modèle de l'enrichissement ; vide ⇒ {@code app.atelier.model} (justesse avant coût)
 */
@ConfigurationProperties(prefix = "app.atelier.enrich")
public record PlumeProperties(Boolean enabled, String model) {

    public PlumeProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (model != null && model.isBlank()) {
            model = null;
        }
    }

    /** Vrai si Plume peut appeler le fournisseur. */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
