package fr.claudegateway.atelier.journey;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 * <b>Un chantier du sujet</b> (F-176 / SF-176-11, décision D9 ; F-206) : son titre, ses dates, son
 * diagnostic et son plan. Clos ({@code CLOSED}) : archivé à la clôture avec le plan validé final,
 * consultable depuis l'en-tête. Ouvert ({@code OPEN}, F-206) : son état complet (phase, plan courant et
 * validé, diagnostic, propositions) — plusieurs chantiers d'un même sujet avancent en parallèle ; celui en
 * focus a sa copie de travail dans {@link SubjectJourney}.
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

    /** {@code null} tant que le chantier est ouvert (F-206). */
    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    /** {@code OPEN} ou {@code CLOSED} (F-206). */
    @Column(name = "status", nullable = false, length = 8)
    private String status;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", length = 16)
    private JourneyPhase phase;

    @Column(name = "phase_changed_at")
    private OffsetDateTime phaseChangedAt;

    @Column(name = "validated_plan_json", length = 1000000)
    private String validatedPlanJson;

    @Column(name = "validated_version")
    private Integer validatedVersion;

    @Column(name = "plan_validated_at")
    private OffsetDateTime planValidatedAt;

    @Column(name = "diagnosis_evidence", length = 1000000)
    private String diagnosisEvidence;

    @Column(name = "diagnosis_proposed_at")
    private OffsetDateTime diagnosisProposedAt;

    @Column(name = "close_proposed_at")
    private OffsetDateTime closeProposedAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /**
     * Le worktree git du chantier (F-221 / SF-221-06), relatif à la racine du poste
     * ({@code .atelier-chantiers/<id>}) ; {@code null} = la copie principale. Posé quand le chantier démarre
     * alors qu'un autre fil du sujet tourne ; collant jusqu'à sa clôture.
     */
    @Column(name = "worktree_path", length = 255)
    private String worktreePath;

    /** La branche du worktree ({@code atelier/chantier-<id>}), où le chantier committe son travail. */
    @Column(name = "worktree_branch", length = 160)
    private String worktreeBranch;

    @Column(name = "diagnosis", length = 1000000)
    private String diagnosis;

    @Column(name = "diagnosis_confidence", length = 16)
    private String diagnosisConfidence;

    /** Clos : le plan validé final (ou, à défaut, le dernier plan posé). Ouvert : le plan courant. JSON. */
    @Column(name = "plan_json", length = 1000000)
    private String planJson;

    @Column(name = "plan_version")
    private Integer planVersion;

    /** Statut d'un chantier ouvert (F-206). */
    public static final String OPEN = "OPEN";
    /** Statut d'un chantier clos. */
    public static final String CLOSED = "CLOSED";

    /** Vrai si le chantier est ouvert (F-206). */
    public boolean isOpen() {
        return OPEN.equals(status);
    }

    /** Au-delà, un chantier en Exécution sans aucun geste est « en sommeil » (F-222 / SF-222-03). */
    public static final int STALE_DAYS = 3;

    /**
     * <b>Chantier zombie</b> (F-222 / SF-222-03) : ouvert, en Exécution, et sans aucun geste (sa ligne n'a pas bougé)
     * depuis {@link #STALE_DAYS} jours. Rendu visible avec [Clore], retiré du détail de la note de tour ; jamais clos
     * en silence.
     */
    public boolean isStale(OffsetDateTime now) {
        return isOpen() && phase == JourneyPhase.EXECUTION && updatedAt != null && now != null
                && updatedAt.isBefore(now.minusDays(STALE_DAYS));
    }
}
