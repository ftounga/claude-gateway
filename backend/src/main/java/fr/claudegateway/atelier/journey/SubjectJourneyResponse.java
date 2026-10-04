package fr.claudegateway.atelier.journey;

import java.time.OffsetDateTime;

/**
 * <b>Le parcours d'un terminal tel que l'écran le lit</b> (F-176). Jamais d'identifiant de compte.
 *
 * @param mode             {@code LIBRE} ou {@code GUIDE}
 * @param phase            la phase courante ; {@code null} si le sujet n'a jamais été guidé
 * @param phaseLabel       son libellé (« Investigation »…), {@code null} sans phase
 * @param phaseChangedAt   depuis quand le sujet est dans cette phase
 * @param guidedProposal   la proposition du mode guidé qui attend un geste, ou {@code null} (SF-176-02)
 * @param guidedDeclined   vrai si l'utilisateur a choisi de rester libre sur ce sujet (SF-176-02)
 */
public record SubjectJourneyResponse(
        String mode,
        String phase,
        String phaseLabel,
        OffsetDateTime phaseChangedAt,
        GuidedProposal guidedProposal,
        boolean guidedDeclined) {

    /** La carte [Passer en guidé] [Rester libre] : pourquoi l'agent la propose, et depuis quand. */
    public record GuidedProposal(String reason, OffsetDateTime proposedAt) {
    }

    public static SubjectJourneyResponse from(SubjectJourney journey) {
        JourneyPhase phase = journey.getPhase();
        GuidedProposal proposal = journey.getGuidedProposedAt() == null || journey.isGuided()
                ? null
                : new GuidedProposal(journey.getGuidedProposalReason(), journey.getGuidedProposedAt());
        return new SubjectJourneyResponse(
                journey.getMode() == null ? JourneyMode.LIBRE.name() : journey.getMode().name(),
                phase == null ? null : phase.name(),
                phase == null ? null : phase.label(),
                journey.getPhaseChangedAt(),
                proposal,
                journey.getGuidedDeclinedAt() != null);
    }
}
