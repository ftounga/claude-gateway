package fr.claudegateway.teams.meeting.dto;

import java.util.UUID;

/**
 * Demande de création d'un artefact réunion (F-128 / SF-128-01) — « Rejoindre & capturer ».
 *
 * @param meetingUrl          URL de la réunion à ouvrir dans le Chrome managé (obligatoire, http(s))
 * @param title               titre libre pour le compte rendu (optionnel)
 * @param subjectId           sujet du Radar à enrichir (optionnel, même poste/compte)
 * @param consentAcknowledged l'utilisateur déclare avoir prévenu les participants (doit valoir true)
 * @param retentionDays       durée de conservation en jours (optionnel, défaut 30, borné [1;365])
 */
public record CreateMeetingRequest(
        String meetingUrl,
        String title,
        UUID subjectId,
        Boolean consentAcknowledged,
        Integer retentionDays) {
}
