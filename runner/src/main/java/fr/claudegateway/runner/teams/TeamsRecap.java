package fr.claudegateway.runner.teams;

import java.time.Instant;

/**
 * <b>Le récapitulatif d'une réunion</b> — l'emplacement de son enregistrement et de sa
 * transcription (F-89 / SF-89-13).
 *
 * <p>Lu dans l'objet de collaboration Teams ({@code readcollabobject}), dont le relevé réel du
 * 2026-09-15 (CAGIP, SF-89-12) a enfin donné la forme : {@code resources[].metadata} porte
 * {@code driveId} + {@code driveItemId} (l'emplacement SharePoint du fichier), {@code threadId},
 * {@code callId} et {@code meetingJoinUrl}. C'est ce qui permet de <b>localiser</b> l'enregistrement
 * d'une réunion passée, là où le message d'enregistrement ne l'a pas encore été vu.</p>
 *
 * <p><b>Ce n'est pas une réunion.</b> L'objet de collaboration ne porte ni objet ni participants ;
 * il ne saurait donc être rendu comme un {@link TeamsMeeting} (dont {@link TeamsMeeting#isReadable()}
 * exige un sujet). Il est rattaché à sa réunion par le <b>fil</b> ({@link #conversationId()} =
 * {@code threadId}), le même identifiant que porte la réunion.</p>
 *
 * <p><b>Rien n'est recopié en aveugle</b> : chaque champ est lu par son nom, si bien qu'aucun autre
 * champ du corps (un jeton, un secret) ne peut franchir cette couche.</p>
 *
 * @param conversationId le fil de la réunion ({@code threadId}), clé de rattachement ; ou {@code ""}
 * @param startedAt      début de l'enregistrement, ou {@code null}
 * @param endedAt        fin de l'enregistrement, ou {@code null}
 * @param callId         identifiant d'appel, ou {@code ""}
 * @param driveId        drive SharePoint où l'enregistrement est rangé, ou {@code ""}
 * @param driveItemId    élément (fichier) dans ce drive, ou {@code ""}
 * @param joinUrl        lien de participation de la réunion ({@code meetingJoinUrl}), ou {@code ""}
 */
public record TeamsRecap(String conversationId, Instant startedAt, Instant endedAt, String callId,
        String driveId, String driveItemId, String joinUrl) {

    public TeamsRecap {
        conversationId = conversationId == null ? "" : conversationId.strip();
        callId = callId == null ? "" : callId.strip();
        driveId = driveId == null ? "" : driveId.strip();
        driveItemId = driveItemId == null ? "" : driveItemId.strip();
        joinUrl = joinUrl == null ? "" : joinUrl.strip();
    }

    /**
     * Un récapitulatif n'est <b>utile</b> que s'il rattache un fil à un emplacement : sans le fil, on
     * ne sait à quelle réunion il appartient ; sans un identifiant de drive, il ne localise rien.
     */
    public boolean isReadable() {
        return !conversationId.isEmpty() && (!driveItemId.isEmpty() || !driveId.isEmpty());
    }
}
