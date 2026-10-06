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
 * <b>Un chantier clos du sujet</b> (F-176 / SF-176-11, décision D9) : son titre, ses dates, son
 * diagnostic et le plan validé final — archivé à la clôture, consultable depuis l'en-tête.
 *
 * <p><b>Isolation.</b> {@code user_id} sur chaque ligne ; toute lecture filtre
 * {@code (user_id, workspace_id)} après {@code requireOwned}.</p>
 */
@Entity
@Table(name = "subject_journey_chantiers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubjectJourneyChantier {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "opened_at")
    private OffsetDateTime openedAt;

    @Column(name = "closed_at", nullable = false)
    private OffsetDateTime closedAt;

    @Column(name = "diagnosis", length = 1000000)
    private String diagnosis;

    @Column(name = "diagnosis_confidence", length = 16)
    private String diagnosisConfidence;

    /** Le plan validé final (ou, à défaut, le dernier plan posé), JSON. */
    @Column(name = "plan_json", length = 1000000)
    private String planJson;

    @Column(name = "plan_version")
    private Integer planVersion;
}
