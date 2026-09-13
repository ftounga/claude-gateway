package fr.claudegateway.radar;

/**
 * État d'un sujet du Radar (F-99, cadrage §3 et §6).
 *
 * <p>Les règles qui font passer d'un état à l'autre vivent dans le registre, jamais dans l'écran :
 * un sujet n'est <b>jamais clos par le silence</b> ({@link #DORMANT}), une clôture repérée dans une
 * source n'est qu'une <b>proposition</b> ({@link #CLOSE_PROPOSED}), et seul l'utilisateur clôt
 * ({@link #CLOSED}).</p>
 */
public enum RadarSubjectState {

    /** Découvert par la synchro, présenté comme tel. */
    NEW,

    /** Le sujet avance. */
    ADVANCING,

    /** En attente d'un tiers ou d'une échéance. */
    WAITING,

    /** Bloqué. */
    BLOCKED,

    /** En sommeil : silence prolongé. Jamais une clôture. */
    DORMANT,

    /** « Clos ? » : un signal explicite a été lu, l'utilisateur confirme ou refuse. */
    CLOSE_PROPOSED,

    /** Clos : dit ou confirmé par l'utilisateur. */
    CLOSED;

    /** États d'un sujet ouvert — ceux que le silence peut mettre en sommeil. */
    public boolean isOpenWork() {
        return this == NEW || this == ADVANCING || this == WAITING || this == BLOCKED;
    }
}
