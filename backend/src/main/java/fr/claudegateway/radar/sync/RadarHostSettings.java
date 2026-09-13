package fr.claudegateway.radar.sync;

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
 * Les réglages Radar <b>d'un poste</b> (F-100) : la vérification guidée (SF-100-01) et, depuis
 * SF-100-02, la planification de la synchro du soir. Une ligne par poste, {@code user_id} +
 * {@code host_id}.
 */
@Entity
@Table(name = "radar_host_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarHostSettings {

    /** Borne du JSON de vérification. */
    public static final int MAX_VERIFICATION_CHARS = 8_000;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** JSON des quatre cases ; {@code null} tant qu'aucune vérification n'a été faite. */
    @Column(name = "verification", length = MAX_VERIFICATION_CHARS)
    private String verification;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
