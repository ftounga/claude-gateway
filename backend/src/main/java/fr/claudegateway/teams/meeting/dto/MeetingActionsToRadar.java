package fr.claudegateway.teams.meeting.dto;

import java.util.List;
import java.util.UUID;

/**
 * Le bilan d'un <b>push des actions d'une réunion vers le Radar</b> (F-128 / SF-128-06).
 *
 * <p>Les actions retenues par le consultant deviennent des engagements « À faire par moi »
 * ({@code ME_TO_OTHER}) sur un sujet du Radar, avec la <b>réunion pour preuve</b> et annulables comme
 * tout le Radar. Ce bilan dit ce qui a été poussé, sur quel sujet, et pourquoi rien parfois.</p>
 *
 * @param subjectId    le sujet cible, ou {@code null} si aucun n'a pu être désigné
 * @param subjectName  le nom du sujet cible, ou {@code null}
 * @param added        le nombre d'engagements <b>réellement créés</b>
 * @param actions      le sort de chaque action retenue, dans l'ordre reçu
 * @param evidenceId   la preuve « réunion » rangée (partagée par tous les engagements), ou {@code null}
 * @param needsSubject vrai si aucun sujet cible n'a pu être déterminé : rien n'est écrit, le consultant
 *                     doit en désigner un
 * @param note         un mot lisible quand rien n'est écrit (aucun sujet, aucune action, déjà poussé),
 *                     ou {@code null}
 */
public record MeetingActionsToRadar(UUID subjectId, String subjectName, int added,
        List<PushedAction> actions, UUID evidenceId, boolean needsSubject, String note) {

    /** Aucun sujet cible : ni requête, ni réunion ne le désigne — rien n'est écrit. */
    public static MeetingActionsToRadar needsSubject(String note) {
        return new MeetingActionsToRadar(null, null, 0, List.of(), null, true, note);
    }

    /** Aucune action à pousser : rien n'est écrit. */
    public static MeetingActionsToRadar nothing(UUID subjectId, String subjectName, String note) {
        return new MeetingActionsToRadar(subjectId, subjectName, 0, List.of(), null, false, note);
    }

    /**
     * Le sort d'une action poussée.
     *
     * @param description le texte de l'action, tel que retenu (borné, traité comme une donnée)
     * @param status      {@code ADDED} (engagement créé) ou {@code SKIPPED} (déjà poussée : pas de doublon)
     */
    public record PushedAction(String description, String status) {

        public static final String ADDED = "ADDED";
        public static final String SKIPPED = "SKIPPED";
    }
}
