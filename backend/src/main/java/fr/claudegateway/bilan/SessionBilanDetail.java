package fr.claudegateway.bilan;

import java.util.List;

/**
 * Un bilan <b>ouvert</b> (F-155 / SF-155-04) : ses chiffres de tête, et les deux photographies —
 * ce qui a été fait et ce que ça a coûté (le relevé), ce qui aurait mieux valu (les suggestions,
 * chacune avec sa mesure et son gain).
 *
 * @param headline  les chiffres de la liste
 * @param ledger    le relevé, {@code null} si la photographie manque
 * @param suggestions les suggestions retenues, vides s'il n'y avait rien à signaler
 * @param patterns    les <b>motifs</b> qui reviennent (F-155 / SF-155-05) : quand la même
 *                    suggestion revient séance après séance, ce n'est plus une habitude à corriger
 */
public record SessionBilanDetail(
        SessionBilanView headline,
        SessionLedger ledger,
        List<SessionSuggestion> suggestions,
        List<SessionPattern> patterns) {
}
