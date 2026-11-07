package fr.claudegateway.jarvis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>Le visage de Jarvis</b> (F-250, porte P5) : deux coupe-circuits, allumés par défaut.
 *
 * @param enabled      {@code APP_JARVIS_FACE_ENABLED} — le visage remplace le cœur 3D dans {@code /jarvis} ; éteint,
 *                     l'écran garde le cœur F-236 (et la voix ne demande plus d'alignement)
 * @param alignEnabled {@code APP_JARVIS_FACE_ALIGN_ENABLED} — la voix relaie l'alignement par caractère (lèvres
 *                     synchronisées) ; éteint, chemin mp3 seul et lèvres sur le niveau sonore (repli amplitude)
 */
@ConfigurationProperties(prefix = "app.jarvis.face")
public record JarvisFaceProperties(Boolean enabled, Boolean alignEnabled) {

    public JarvisFaceProperties {
        enabled = enabled == null || enabled;
        alignEnabled = alignEnabled == null || alignEnabled;
    }

    /** Vrai si l'écran doit demander l'alignement : visage allumé ET alignement allumé. */
    public boolean aligns() {
        return enabled && alignEnabled;
    }
}
