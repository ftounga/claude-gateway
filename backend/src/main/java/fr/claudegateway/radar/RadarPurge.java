package fr.claudegateway.radar;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 * La <b>trace</b> d'une purge du Radar d'un poste (F-99 / SF-99-05) : raison, instant, compteurs.
 * Aucun contenu — c'est la preuve qu'on a effacé, pas une copie de ce qui l'a été.
 */
@Entity
@Table(name = "radar_purges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarPurge {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 24)
    private RadarPurgeReason reason;

    @Column(name = "purged_at", nullable = false, updatable = false)
    private OffsetDateTime purgedAt;

    @Column(name = "subjects_count", nullable = false, updatable = false)
    private int subjectsCount;

    @Column(name = "evidence_count", nullable = false, updatable = false)
    private int evidenceCount;
}
