package fr.claudegateway.images;

/**
 * Le <b>statut lisible</b> d'une image générée (F-142 / SF-142-04) : la génération est bornée et son
 * état est persistant (règle async CLAUDE.md — statut consultable).
 */
public enum GeneratedImageStatus {
    /** Ligne créée, avant l'appel au fournisseur. */
    PENDING,
    /** Image générée, rangée en stockage objet. */
    READY,
    /** Génération en échec (fournisseur, timeout, taille) — le motif accompagne la ligne. */
    FAILED
}
