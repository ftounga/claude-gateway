package fr.claudegateway.atelier.journey;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 * <b>Une version du plan d'un chantier</b> (F-208 / SF-208-02) : écrite à chaque {@code set_subject_plan}
 * accepté, jamais modifiée. L'écran en déduit ce qui a changé d'une version à l'autre.
 *
 * <p><b>Isolation.</b> {@code user_id} sur chaque ligne ; toute lecture filtre
 * {@code (user_id, workspace_id)} après {@code requireOwned}.</p>
 */
@Entity
@Table(name = "subject_journey_plan_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubjectJourneyPlanVersion {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "chantier_number", nullable = false, updatable = false)
    private int chantierNumber;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "plan_json", updatable = false)
    private String planJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
