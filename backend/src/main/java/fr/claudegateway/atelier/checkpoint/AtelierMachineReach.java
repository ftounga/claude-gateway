package fr.claudegateway.atelier.checkpoint;

/**
 * Ce que le tour a <b>constaté</b> de la machine de l'utilisateur (F-93 / SF-93-04).
 *
 * <p>Un contrôle de fin de tour qui exige d'écrire sur la machine n'a de sens que si la machine
 * répond. Quand le runner est déconnecté, refuser la clôture pour réclamer une écriture impossible
 * fait tourner le modèle jusqu'à la borne de F-50, à trois appels payés — et l'utilisateur lit trois
 * fois « je ne peux pas écrire ». Cet état permet au contrôle de distinguer « n'a pas rangé » de
 * « ne pouvait pas ranger ».</p>
 *
 * <p><b>Observé, jamais sondé</b> : il vient des appels runner réellement faits pendant le tour, et
 * le dernier fait foi.</p>
 */
public enum AtelierMachineReach {

    /** Aucun appel runner n'a renseigné l'état (tour sans outil machine, cible hors runner). */
    UNKNOWN,

    /** Le dernier appel runner du tour a reçu une réponse du runner. */
    REACHED,

    /** Le dernier appel runner du tour a été refusé au transport : poste hors ligne. */
    OFFLINE
}
