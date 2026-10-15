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
 * <b>Une routine du sujet</b> (F-183 / SF-183-01, D1) : un travail récurrent que le terminal d'un
 * sujet exécute <b>sans écran ouvert</b> — « chaque matin à 8 h, fais le point sur les tickets ».
 *
 * <p><b>Isolation.</b> Toute lecture et toute écriture filtrent {@code (user_id, workspace_id)} ; un
 * sujet d'un autre compte est introuvable (404).</p>
 *
 * <p>{@code lastSlotAt} est le dernier créneau <b>consommé</b> : posé sur le créneau dû à la
 * déclaration (une routine ne part jamais pour un créneau d'avant elle) et avancé par un
 * {@code UPDATE} conditionnel qui sert de verrou entre pods (SF-183-02).</p>
 */
@Entity
@Table(name = "subject_routines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubjectRoutine {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** La consigne, envoyée telle quelle comme demande du tour (jamais dans la consigne système). */
    @Column(name = "instruction", nullable = false, length = 4000)
    private String instruction;

    @Enumerated(EnumType.STRING)
    @Column(name = "cadence", nullable = false, length = 16)
    private RoutineCadence cadence;

    /** 1 = lundi … 7 = dimanche ; seulement pour {@link RoutineCadence#WEEKLY}. */
    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    /** {@code HH:mm}, dans {@link #timeZone}. */
    @Column(name = "run_time", nullable = false, length = 5)
    private String runTime;

    /** Fuseau IANA du poste. */
    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    /** Budget en dollars d'une exécution (D3). */
    @Column(name = "max_run_usd", nullable = false, precision = 10, scale = 2)
    private BigDecimal maxRunUsd;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Proposée par l'agent et pas encore validée : rien ne part tant qu'elle l'est. */
    @Builder.Default
    @Column(name = "proposed", nullable = false)
    private boolean proposed = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 16)
    private RoutineOrigin origin;

    @Column(name = "last_slot_at")
    private OffsetDateTime lastSlotAt;

    /** Le créneau déjà noté manqué, poste hors ligne (SF-183-02) : une seule ligne par créneau. */
    @Column(name = "missed_slot_at")
    private OffsetDateTime missedSlotAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Vrai si le planificateur doit l'examiner : active et validée. */
    public boolean isScheduled() {
        return active && !proposed;
    }
}
