package fr.claudegateway.radar;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Une personne de l'annuaire d'un poste (F-99 / SF-99-01) : l'identité que la source fournit, rien
 * de plus. Données personnelles de tiers : minimisées (cadrage §14), purgées avec le Radar.
 */
@Entity
@Table(name = "radar_people")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarPerson {

    public static final int MAX_SOURCE_KEY_LENGTH = 320;
    public static final int MAX_NAME_LENGTH = 200;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Identité de source normalisée (adresse, identifiant Teams), unique dans le périmètre. */
    @Column(name = "source_key", nullable = false, length = MAX_SOURCE_KEY_LENGTH)
    private String sourceKey;

    @Column(name = "display_name", nullable = false, length = MAX_NAME_LENGTH)
    private String displayName;

    /** Fonction, seulement si la source la fournit. */
    @Column(name = "job_title", length = MAX_NAME_LENGTH)
    private String jobTitle;

    @Column(name = "last_interaction_at")
    private OffsetDateTime lastInteractionAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
