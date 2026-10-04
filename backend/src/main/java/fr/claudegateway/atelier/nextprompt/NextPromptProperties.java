package fr.claudegateway.atelier.nextprompt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coupe-circuit de <b>la suite prédite</b> (F-144 / SF-144-02, décision D6) :
 * {@code APP_ATELIER_NEXT_PROMPT_ENABLED}, vrai par défaut.
 *
 * <p>Un seul constructeur (compact) : un second casserait le démarrage du contexte Spring.</p>
 *
 * @param enabled faux ⇒ aucun appel au fournisseur, l'écran retombe sur les puces SF-144-01
 */
@ConfigurationProperties(prefix = "app.atelier.next-prompt")
public record NextPromptProperties(Boolean enabled) {

    public NextPromptProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
    }

    /** Vrai si la suite prédite peut appeler le fournisseur. */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
