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
 * Une synchro du Radar (F-99 / SF-99-01) : début, fin, issue, <b>couverture</b> et consommation. La
 * couverture est un document JSON dont F-100 fixe la forme (curseur, éléments lus, échecs par source).
 */
@Entity
@Table(name = "radar_syncs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarSync {

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
    @Column(name = "status", nullable = false, length = 16)
    private RadarSyncStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "coverage", columnDefinition = "text")
    private String coverage;

    @Column(name = "consumed_tokens", nullable = false)
    private long consumedTokens;

    /** Ce qui a lancé la synchro (F-100 / SF-100-02) ; {@code null} pour une synchro antérieure. */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", length = 16)
    private RadarSyncTrigger triggerKind;

    /** Le créneau du soir auquel la synchro répond (planifiée ou rattrapée), ou {@code null}. */
    @Column(name = "scheduled_for")
    private OffsetDateTime scheduledFor;

    /** Dernier battement du runner. */
    @Column(name = "heartbeat_at")
    private OffsetDateTime heartbeatAt;

    /** Où en est la collecte (JSON borné). */
    @Column(name = "progress", length = 4000)
    private String progress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
