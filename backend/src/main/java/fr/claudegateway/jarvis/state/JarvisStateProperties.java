package fr.claudegateway.jarvis.state;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * <b>Les coupe-circuits du registre d'état</b> (F-247, D12) — {@code APP_JARVIS_STATE_*}, tous <b>activés</b> par
 * défaut (D3, décision PO) : revenir en arrière sans déployer.
 *
 * <p>Un seul constructeur (record compact) : un second casserait le démarrage du contexte Spring.</p>
 *
 * @param enabled         tout le registre ({@code APP_JARVIS_STATE_ENABLED}) : faux ⇒ aucune écriture, aucune lecture
 * @param extractEnabled  l'extraction des sorties d'outils des tours (SF-247-04/05)
 * @param notesEnabled    les constats rédigés en différé (SF-247-14)
 * @param releveEnabled   le relevé automatique (SF-247-07)
 * @param capturesEnabled la lecture des captures de conversations (SF-247-12)
 * @param blockEnabled    le bloc « État connu » et l'outil {@code etat} du cerveau rapide (SF-247-10)
 * @param maxFactsPerHost plafond de faits par client (défaut 10 000) ; au-delà, les plus anciens sont purgés
 */
@ConfigurationProperties(prefix = "app.jarvis.state")
public record JarvisStateProperties(Boolean enabled, Boolean extractEnabled, Boolean notesEnabled,
        Boolean releveEnabled, Boolean capturesEnabled, Boolean blockEnabled, Integer maxFactsPerHost) {

    public static final int DEFAULT_MAX_FACTS = 10_000;

    public JarvisStateProperties {
        enabled = !Boolean.FALSE.equals(enabled);
        extractEnabled = !Boolean.FALSE.equals(extractEnabled);
        notesEnabled = !Boolean.FALSE.equals(notesEnabled);
        releveEnabled = !Boolean.FALSE.equals(releveEnabled);
        capturesEnabled = !Boolean.FALSE.equals(capturesEnabled);
        blockEnabled = !Boolean.FALSE.equals(blockEnabled);
        maxFactsPerHost = maxFactsPerHost == null || maxFactsPerHost <= 0 ? DEFAULT_MAX_FACTS : maxFactsPerHost;
    }

    /** Tout activé (tests). */
    public static JarvisStateProperties defaults() {
        return new JarvisStateProperties(null, null, null, null, null, null, null);
    }

    public boolean extract() {
        return enabled && extractEnabled;
    }

    public boolean notes() {
        return enabled && notesEnabled;
    }

    public boolean releve() {
        return enabled && releveEnabled;
    }

    public boolean captures() {
        return enabled && capturesEnabled;
    }

    public boolean block() {
        return enabled && blockEnabled;
    }

    /** Active la configuration du registre. */
    @Configuration
    @EnableConfigurationProperties(JarvisStateProperties.class)
    static class Config {
    }
}
