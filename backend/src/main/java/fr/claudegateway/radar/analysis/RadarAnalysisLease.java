package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 * Le <b>bail d'analyse</b> d'un poste (F-101 / SF-101-01) : un seul traitement par poste à la fois,
 * tous pods confondus. L'ordre des lots compte — un sujet créé par un lot doit être connu du suivant.
 */
@Entity
@Table(name = "radar_analysis_leases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarAnalysisLease {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "owner", nullable = false, length = 64)
    private String owner;

    @Column(name = "leased_until", nullable = false)
    private OffsetDateTime leasedUntil;
}
