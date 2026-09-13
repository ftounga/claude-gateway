package fr.claudegateway.radar;

/** Statut d'un engagement (F-99, cadrage §3). */
public enum RadarCommitmentStatus {

    /** Ouvert. */
    OPEN,

    /** Tenu. */
    KEPT,

    /** Reporté. */
    POSTPONED,

    /** Abandonné. */
    ABANDONED;

    /** Un engagement qui reste à tenir : ouvert ou reporté. */
    public boolean isPending() {
        return this == OPEN || this == POSTPONED;
    }
}
