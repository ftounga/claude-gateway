package fr.claudegateway.presentations;

/**
 * L'espace de rangement d'une présentation (F-129 / SF-129-02) : la <b>Forge</b> côté poste, la
 * <b>Vigie</b> côté client. Même partition que les pages (F-109) et le droit d'espace du terminal.
 */
public enum PresentationSpace {

    /** Côté poste (terminal de projet). */
    FORGE,

    /** Côté client (terminal Teams). */
    VIGIE
}
