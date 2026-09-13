package fr.claudegateway.radar;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Ce qu'une preuve justifie sur un sujet (F-99 / SF-99-01) — voir {@link RadarLinkKind}.
 *
 * <p>{@link #targetId} désigne la phrase, l'engagement ou le rôle justifié ; il est vide pour les
 * liens qui portent sur le sujet lui-même (chronologie, état, prochaine étape, échéance).</p>
 */
@Entity
@Table(name = "radar_evidence_links")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarEvidenceLink {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "evidence_id", nullable = false, updatable = false)
    private UUID evidenceId;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, updatable = false, length = 24)
    private RadarLinkKind targetKind;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
