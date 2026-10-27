package fr.claudegateway.atelier.autonomy;

import java.math.BigDecimal;
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
 * <b>Une course autonome d'un chantier</b> (F-219 / SF-219-02) : des instants, un statut, un coût, une raison
 * d'arrêt lisible. Aucun contenu de tour. {@code status = RUNNING} et {@code finished_at} nul tant que la
 * boucle tourne — ce que la vérification avant déploiement interroge.
 *
 * <p><b>Isolation</b> : {@code user_id} + {@code workspace_id} sur chaque ligne ; toute lecture filtre par
 * {@code user_id}.</p>
 */
@Entity
@Table(name = "chantier_autonomy_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChantierAutonomyRun {

    /** Longueur maximale de la raison d'arrêt. */
    public static final int MAX_REASON = 300;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "chantier_number", nullable = false, updatable = false)
    private int chantierNumber;

    @Column(name = "chantier_title", length = 200)
    private String chantierTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AutonomyRunStatus status;

    @Column(name = "turns", nullable = false)
    private int turns;

    @Column(name = "steps_done", nullable = false)
    private int stepsDone;

    @Column(name = "steps_total", nullable = false)
    private int stepsTotal;

    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    @Builder.Default
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Column(name = "stop_reason", length = MAX_REASON)
    private String stopReason;

    @Column(name = "error_code", length = 40)
    private String errorCode;

    @Column(name = "turn_id")
    private UUID turnId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "heartbeat_at", nullable = false)
    private OffsetDateTime heartbeatAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;
}
