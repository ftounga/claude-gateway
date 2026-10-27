package fr.claudegateway.atelier.dashboard;

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
 * <b>Un chantier reporté</b> (F-220 / SF-220-05) : jusqu'à {@code snoozedUntil}, il quitte les groupes du tableau
 * du client et le rappel des dormants l'ignore. Une ligne par chantier ; un report remplace le précédent.
 *
 * <p><b>Isolation</b> : {@code user_id} + {@code workspace_id} sur chaque ligne ; toute lecture filtre par
 * {@code user_id}.</p>
 */
@Entity
@Table(name = "chantier_snoozes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChantierSnooze {

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

    @Column(name = "snoozed_until", nullable = false)
    private OffsetDateTime snoozedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
