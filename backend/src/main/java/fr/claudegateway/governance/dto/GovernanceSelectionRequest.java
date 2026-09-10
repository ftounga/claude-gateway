package fr.claudegateway.governance.dto;

/**
 * Retenir un paquet, ou changer son drapeau (F-51 / SF-51-02).
 *
 * @param defaultApplied embarquer ce paquet dans mes projets à venir ; {@code null} vaut
 *                       {@code false} — le défaut n'est jamais l'automatisme
 */
public record GovernanceSelectionRequest(Boolean defaultApplied) {

    /** Le drapeau, en ne laissant jamais un corps absent décider à la place de l'utilisateur. */
    public boolean defaultAppliedOrFalse() {
        return Boolean.TRUE.equals(defaultApplied);
    }
}
