package fr.claudegateway.images;

/**
 * L'espace de rangement d'une image générée (F-142 / SF-142-04) — même partition que les pages (F-109)
 * et les présentations (F-129) : la Forge (projets/postes) ou la Vigie (terminal Teams d'un client).
 */
public enum ImageSpace {
    /** Espace de fabrication (projets, postes). */
    FORGE,
    /** Espace de veille (terminal Teams d'un client). */
    VIGIE
}
