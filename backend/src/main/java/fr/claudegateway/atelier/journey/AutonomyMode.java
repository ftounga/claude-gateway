package fr.claudegateway.atelier.journey;

import java.util.Locale;

/**
 * <b>Le mode d'une course autonome</b> (F-219 / SF-219-05). Vit dans le parcours pour que l'outil de l'agent
 * le porte sans dépendre du paquet de l'autonomie.
 */
public enum AutonomyMode {
    /** Préflight entièrement vert ; exécute tout le chantier d'un trait (SF-219-02). */
    STRICT,
    /**
     * Avancer jusqu'au blocage : exécute les étapes à la portée de l'outil et celles qu'elles débloquent,
     * contourne ce qui attend une décision ou quelqu'un, puis s'arrête proprement.
     */
    UNTIL_BLOCKED;

    /**
     * Le mode lu tel que la route ou l'outil le reçoit : absent ou vide = {@link #STRICT}.
     *
     * @throws IllegalArgumentException mode inconnu
     */
    public static AutonomyMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return STRICT;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Mode inconnu : « " + raw.strip() + " » (STRICT ou UNTIL_BLOCKED).");
        }
    }
}
