package fr.claudegateway.radar;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Un <b>engagement</b> (F-99 / SF-99-01) : qui doit quoi à qui. « Moi » est représenté par une
 * personne vide — voir {@link RadarCommitmentDirection}.
 */
@Entity
@Table(name = "radar_commitments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarCommitment {

    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_EXTRACTION_KEY_LENGTH = 128;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private RadarCommitmentDirection direction;

    @Column(name = "description", nullable = false, length = MAX_DESCRIPTION_LENGTH)
    private String description;

    /** Qui doit ; vide = moi. */
    @Column(name = "from_person_id")
    private UUID fromPersonId;

    /** À qui ; vide = moi (ou personne de précis). Pour une mise en relation : la personne A. */
    @Column(name = "to_person_id")
    private UUID toPersonId;

    /** Mise en relation seulement : la personne B. */
    @Column(name = "other_person_id")
    private UUID otherPersonId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Échéance déduite (« jeudi » résolu depuis la date du message) plutôt qu'écrite. */
    @Column(name = "due_deduced", nullable = false)
    private boolean dueDeduced;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RadarCommitmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "certainty", nullable = false, length = 16)
    private RadarCertainty certainty;

    /** Clé d'idempotence fournie par l'analyse, unique dans le périmètre ; facultative. */
    @Column(name = "extraction_key", length = MAX_EXTRACTION_KEY_LENGTH, updatable = false)
    private String extractionKey;

    /** Corrigé par l'utilisateur : une synchro n'en change plus le statut (SF-99-02). */
    @Column(name = "sovereign", nullable = false)
    private boolean sovereign;

    /** « Pas moi » : sort des listes, n'est pas supprimé (SF-99-02). */
    @Column(name = "disowned", nullable = false)
    private boolean disowned;

    /** Instant de la preuve la plus récente, tenu par le registre (F-101 / SF-101-04). */
    @Column(name = "last_evidence_at")
    private OffsetDateTime lastEvidenceAt;

    /** Jour où une relance est due ; recalculé à chaque écriture, jamais saisi (F-101 / SF-101-04). */
    @Column(name = "follow_up_due_on")
    private LocalDate followUpDueOn;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** La relance due suit l'engagement, quelle que soit l'écriture qui l'a changé. */
    @PrePersist
    @PreUpdate
    void refreshFollowUp() {
        followUpDueOn = RadarFollowUp.dueOn(direction, status, disowned, dueDate, lastEvidenceAt);
    }

    /** Retient la preuve la plus récente. */
    public void noteEvidence(OffsetDateTime occurredAt) {
        if (occurredAt != null && (lastEvidenceAt == null || lastEvidenceAt.isBefore(occurredAt))) {
            lastEvidenceAt = occurredAt;
        }
        refreshFollowUp();
    }

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
