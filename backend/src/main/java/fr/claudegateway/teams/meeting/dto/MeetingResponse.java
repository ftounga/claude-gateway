package fr.claudegateway.teams.meeting.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.teams.meeting.Meeting;

/**
 * Vue d'un artefact réunion rendue au client (F-128 / SF-128-01). Distincte de la requête : jamais
 * l'entité JPA directement.
 */
public record MeetingResponse(
        UUID id,
        UUID hostId,
        UUID subjectId,
        String title,
        String meetingUrl,
        String state,
        boolean consentAcknowledged,
        int retentionDays,
        String captureRef,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        OffsetDateTime createdAt) {

    public static MeetingResponse of(Meeting m) {
        return new MeetingResponse(m.getId(), m.getHostId(), m.getSubjectId(), m.getTitle(),
                m.getMeetingUrl(), m.getState().name(), m.isConsentAcknowledged(), m.getRetentionDays(),
                m.getCaptureRef(), m.getStartedAt(), m.getEndedAt(), m.getCreatedAt());
    }
}
