package fr.claudegateway.atelier.journey;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Le parcours du sujet d'un terminal</b> (F-176) : son mode (Libre / Guidé), sa phase, et ce qui
 * la fait avancer — proposition du mode guidé, plan structuré et sa validation, diagnostic.
 *
 * <p>Une ligne par terminal ({@code workspace_id} = clé). <b>Pas de ligne = Libre</b> : le
 * comportement d'avant F-176, inchangé (décision Q4).</p>
 *
 * <p><b>Isolation.</b> Toute lecture filtre {@code (user_id, workspace_id)} ; le terminal est d'abord
 * vérifié comme possédé ({@code requireOwned}) — un terminal d'autrui est introuvable (404).</p>
 */
@Entity
@Table(name = "subject_journeys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubjectJourney {

    @Id
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private JourneyMode mode;

    /** {@code null} tant que le sujet n'a jamais été guidé. */
    @Enumerated(EnumType.STRING)
    @Column(name = "phase", length = 16)
    private JourneyPhase phase;

    @Column(name = "phase_changed_at")
    private OffsetDateTime phaseChangedAt;

    /** La carte [Passer en guidé] [Rester libre] attend un geste (SF-176-02). */
    @Column(name = "guided_proposed_at")
    private OffsetDateTime guidedProposedAt;

    @Column(name = "guided_proposal_reason", length = 300)
    private String guidedProposalReason;

    /** « Rester libre » : l'agent ne repropose plus le mode guidé sur ce sujet (SF-176-02). */
    @Column(name = "guided_declined_at")
    private OffsetDateTime guidedDeclinedAt;

    /** Le plan courant, JSON (SF-176-03). */
    @Column(name = "plan_json", length = 1000000)
    private String planJson;

    @Column(name = "plan_version", nullable = false)
    private int planVersion;

    /** La dernière version validée du plan, JSON — pour montrer un amendement (SF-176-03/04). */
    @Column(name = "validated_plan_json", length = 1000000)
    private String validatedPlanJson;

    @Column(name = "validated_version")
    private Integer validatedVersion;

    @Column(name = "plan_validated_at")
    private OffsetDateTime planValidatedAt;

    /** Le diagnostic posé en fin d'investigation (SF-176-05). */
    @Column(name = "diagnosis", length = 1000000)
    private String diagnosis;

    @Column(name = "diagnosis_evidence", length = 1000000)
    private String diagnosisEvidence;

    @Column(name = "diagnosis_confidence", length = 16)
    private String diagnosisConfidence;

    /** « Prêt à planifier » attend la confirmation de l'utilisateur (SF-176-05). */
    @Column(name = "diagnosis_proposed_at")
    private OffsetDateTime diagnosisProposedAt;

    /** « Je propose de clore » attend la confirmation de l'utilisateur (SF-176-05). */
    @Column(name = "close_proposed_at")
    private OffsetDateTime closeProposedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Vrai si le sujet est en mode guidé. */
    public boolean isGuided() {
        return mode == JourneyMode.GUIDE;
    }
}
