package fr.claudegateway.atelier.routine;

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
 * <b>Une exécution d'une routine</b> (F-183 / SF-183-02) — ou un créneau manqué. Le journal que la
 * vérification avant déploiement interroge (D5) : {@code status = RUNNING} et {@code finished_at} nul
 * tant que le tour tourne.
 *
 * <p>Aucun contenu de tour : des instants, un statut, un coût, un code d'erreur et une ligne de
 * compte rendu. <b>Isolation</b> : {@code user_id} + {@code workspace_id} sur chaque ligne.</p>
 */
@Entity
@Table(name = "routine_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutineRun {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "routine_id", nullable = false, updatable = false)
    private UUID routineId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /** Le créneau visé ({@code null} pour un lancement manuel). */
    @Column(name = "slot_at")
    private OffsetDateTime slotAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, length = 16)
    private RoutineTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RoutineRunStatus status;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /** Coût du tour, en dollars (somme des tours de l'exécution). */
    @Column(name = "cost_usd", precision = 12, scale = 6)
    private BigDecimal costUsd;

    /** Plafond appliqué à cette exécution, en dollars (SF-183-03). */
    @Column(name = "cap_usd", precision = 10, scale = 2)
    private BigDecimal capUsd;

    /** Le tour vivant (F-84) de l'exécution. */
    @Column(name = "turn_id")
    private UUID turnId;

    @Column(name = "error_code", length = 40)
    private String errorCode;

    /** La ligne de compte rendu (SF-183-04). */
    @Column(name = "summary", length = 300)
    private String summary;

    /** Attentes du sujet créées ou modifiées pendant l'exécution (SF-183-04) ; un compte, aucun contenu. */
    @Column(name = "attentes_updated")
    private Integer attentesUpdated;

    /** La ligne en tête du fil a été fermée par l'utilisateur (SF-183-04). */
    @Column(name = "acknowledged_at")
    private OffsetDateTime acknowledgedAt;
}
