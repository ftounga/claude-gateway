package fr.claudegateway.radar.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.radar.RadarCertainty;
import fr.claudegateway.radar.RadarCommitmentDirection;
import fr.claudegateway.radar.RadarCommitmentStatus;
import fr.claudegateway.radar.RadarEvidenceSource;
import fr.claudegateway.radar.RadarRole;
import fr.claudegateway.radar.RadarSubjectState;
import fr.claudegateway.radar.RadarSyncStatus;

/**
 * Les vues REST du Radar (F-99 / SF-99-01), lues par les écrans à venir (F-102, F-103).
 *
 * <p>Toutes les vues portent les <b>identifiants de preuve</b> de ce qu'elles affirment : l'écran rend
 * chaque phrase avec ses renvois (cadrage §4.1), il n'a jamais à les deviner.</p>
 */
public final class RadarViews {

    private RadarViews() {
    }

    /** Un sujet dans une liste. */
    public record SubjectSummary(UUID id, String name, RadarSubjectState state, String nextStep,
            LocalDate dueDate, OffsetDateTime lastActivityAt, int openCommitments) {
    }

    /** La page d'un sujet. */
    public record SubjectDetail(UUID id, String name, RadarSubjectState state, String nextStep,
            LocalDate dueDate, OffsetDateTime lastActivityAt, OffsetDateTime createdAt,
            List<AliasView> aliases, List<UUID> stateEvidenceIds, List<UUID> nextStepEvidenceIds,
            List<UUID> dueDateEvidenceIds, List<SentenceView> summary, List<RoleView> people,
            List<CommitmentView> commitments, List<EvidenceView> chronology) {
    }

    /** Un autre nom du sujet. */
    public record AliasView(UUID id, String alias) {
    }

    /** Une phrase du résumé et ses renvois. */
    public record SentenceView(UUID id, int position, String text, List<UUID> evidenceIds) {
    }

    /** Une personne et son rôle sur le sujet. */
    public record RoleView(UUID id, UUID personId, String displayName, String jobTitle, RadarRole role,
            List<UUID> evidenceIds) {
    }

    /** Une personne désignée par un engagement. */
    public record PersonRef(UUID id, String displayName) {
    }

    /** Un engagement. Une personne vide, c'est « moi ». */
    public record CommitmentView(UUID id, UUID subjectId, String subjectName,
            RadarCommitmentDirection direction, String description, PersonRef fromPerson,
            PersonRef toPerson, PersonRef otherPerson, LocalDate dueDate, boolean dueDeduced,
            RadarCommitmentStatus status, RadarCertainty certainty, List<UUID> evidenceIds,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** Une preuve. */
    public record EvidenceView(UUID id, RadarEvidenceSource source, String sourceRef,
            OffsetDateTime occurredAt, String quote, String deepLink, UUID authorPersonId) {
    }

    /** Une personne de l'annuaire, ses sujets et ses rôles. */
    public record PersonView(UUID id, String sourceKey, String displayName, String jobTitle,
            OffsetDateTime lastInteractionAt, List<PersonSubjectView> subjects) {
    }

    /** Un sujet d'une personne. */
    public record PersonSubjectView(UUID subjectId, String subjectName, RadarSubjectState state,
            RadarRole role) {
    }

    /** Une synchro. */
    public record SyncView(UUID id, RadarSyncStatus status, OffsetDateTime startedAt,
            OffsetDateTime finishedAt, JsonNode coverage, long consumedTokens) {
    }
}
