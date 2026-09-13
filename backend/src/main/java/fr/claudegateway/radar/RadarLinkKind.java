package fr.claudegateway.radar;

/**
 * Ce qu'une preuve justifie sur un sujet (F-99 / SF-99-01).
 *
 * <p><b>Pas de fait sans preuve</b> (cadrage §4.1) : chaque valeur portée par un sujet — son état, sa
 * prochaine étape, son échéance, une phrase de son résumé, un engagement, un rôle — est accompagnée
 * d'au moins un lien de ce genre. {@link #CHRONOLOGY} range la preuve dans la chronologie du sujet ;
 * toute preuve qui justifie quelque chose y figure aussi.</p>
 */
public enum RadarLinkKind {

    /** La preuve fait partie de la chronologie du sujet. */
    CHRONOLOGY,

    /** Elle justifie l'état courant. */
    STATE,

    /** Elle justifie la prochaine étape. */
    NEXT_STEP,

    /** Elle justifie l'échéance. */
    DUE_DATE,

    /** Elle justifie une phrase du résumé ({@code target_id} = la phrase). */
    SUMMARY,

    /** Elle justifie un engagement ({@code target_id} = l'engagement). */
    COMMITMENT,

    /** Elle justifie un rôle ({@code target_id} = le rôle). */
    ROLE,

    /** Elle est le signal explicite qui propose la clôture (SF-99-04). */
    CLOSE_SIGNAL
}
