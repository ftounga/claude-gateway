package fr.claudegateway.atelier.journey;

import java.time.OffsetDateTime;

/**
 * <b>Le parcours d'un terminal tel que l'écran le lit</b> (F-176). Jamais d'identifiant de compte.
 *
 * @param mode           {@code LIBRE} ou {@code GUIDE}
 * @param phase          la phase courante ; {@code null} si le sujet n'a jamais été guidé
 * @param phaseLabel     son libellé (« Investigation »…), {@code null} sans phase
 * @param phaseChangedAt depuis quand le sujet est dans cette phase
 */
public record SubjectJourneyResponse(
        String mode,
        String phase,
        String phaseLabel,
        OffsetDateTime phaseChangedAt) {

    public static SubjectJourneyResponse from(SubjectJourney journey) {
        JourneyPhase phase = journey.getPhase();
        return new SubjectJourneyResponse(
                journey.getMode() == null ? JourneyMode.LIBRE.name() : journey.getMode().name(),
                phase == null ? null : phase.name(),
                phase == null ? null : phase.label(),
                journey.getPhaseChangedAt());
    }
}
