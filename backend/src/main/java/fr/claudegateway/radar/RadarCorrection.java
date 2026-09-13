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
 * Une ligne du <b>journal des corrections</b> (F-99 / SF-99-02) : ce que l'utilisateur a dit, les
 * valeurs avant et après, et son éventuelle annulation.
 *
 * <p>Les valeurs sont un document JSON qui ne retient <b>que les champs touchés</b> par l'action :
 * c'est ce qui permet d'annuler une correction sans défaire ce qu'une synchro a écrit ailleurs.</p>
 */
@Entity
@Table(name = "radar_corrections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadarCorrection {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Le sujet concerné — celui de l'engagement quand la cible est un engagement. */
    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, updatable = false, length = 16)
    private RadarCorrectionAction.Target targetKind;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 24)
    private RadarCorrectionAction action;

    @Column(name = "before_values", nullable = false, updatable = false, columnDefinition = "text")
    private String beforeValues;

    @Column(name = "after_values", nullable = false, updatable = false, columnDefinition = "text")
    private String afterValues;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "undone_at")
    private OffsetDateTime undoneAt;

    /**
     * La <b>preuve</b> qui a porté cette correction (F-104 / SF-104-01) : la nouvelle de l'utilisateur
     * ({@code USER_NOTE}, {@code PASTED_MAIL}). {@code null} pour un geste de l'écran, qui est sa propre
     * justification.
     */
    @Column(name = "evidence_id")
    private UUID evidenceId;
}
