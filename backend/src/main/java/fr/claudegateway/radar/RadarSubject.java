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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Un <b>sujet</b> du Radar (F-99 / SF-99-01) : l'unité qui traverse les sources — « le MFA », « la
 * migration LDAP ».
 *
 * <p>Les valeurs portées ici (état, prochaine étape, échéance) sont les valeurs <b>courantes</b> ;
 * leurs preuves vivent dans {@code radar_evidence_links}. Le registre refuse d'écrire l'une sans
 * l'autre.</p>
 *
 * <p><b>Isolation.</b> {@link #userId} et {@link #hostId} ; aucune lecture n'existe sans les deux.</p>
 */
@Entity
@Table(name = "radar_subjects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarSubject {

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_NEXT_STEP_LENGTH = 500;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private RadarSubjectState state;

    @Column(name = "next_step", length = MAX_NEXT_STEP_LENGTH)
    private String nextStep;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Instant de la preuve la plus récente de la chronologie. */
    @Column(name = "last_activity_at")
    private OffsetDateTime lastActivityAt;

    /** Le nom a été corrigé par l'utilisateur : une synchro ne le change plus (SF-99-02). */
    @Column(name = "name_sovereign", nullable = false)
    private boolean nameSovereign;

    /** L'état a été dit par l'utilisateur : une synchro ne le change plus (SF-99-02). */
    @Column(name = "state_sovereign", nullable = false)
    private boolean stateSovereign;

    /** La prochaine étape a été dite par l'utilisateur (SF-99-02). */
    @Column(name = "next_step_sovereign", nullable = false)
    private boolean nextStepSovereign;

    /** L'échéance a été dite par l'utilisateur (SF-99-02). */
    @Column(name = "due_date_sovereign", nullable = false)
    private boolean dueDateSovereign;

    /** État d'avant une proposition de clôture ou un sommeil (SF-99-04). */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_state", length = 20)
    private RadarSubjectState previousState;

    /** Un signal explicite a proposé la clôture (SF-99-04). */
    @Column(name = "close_proposed_at")
    private OffsetDateTime closeProposedAt;

    /** Dernier refus d'une proposition de clôture (SF-99-04). */
    @Column(name = "close_rejected_at")
    private OffsetDateTime closeRejectedAt;

    /** Clos par l'utilisateur, jamais par le silence (SF-99-04). */
    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    /** En sommeil depuis (SF-99-04). */
    @Column(name = "dormant_since")
    private OffsetDateTime dormantSince;

    /** Un sujet clos a repris vie : annoncé, pas rouvert (SF-99-04). */
    @Column(name = "woke_at")
    private OffsetDateTime wokeAt;

    /** L'utilisateur a laissé clos malgré le réveil (SF-99-04). */
    @Column(name = "wake_dismissed_at")
    private OffsetDateTime wakeDismissedAt;

    /** Sujet absorbé par une fusion : la cible (SF-99-03). Il reste comme trace. */
    @Column(name = "merged_into_id")
    private UUID mergedIntoId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
