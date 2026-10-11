package fr.claudegateway.atelier.nextsteps;

/**
 * Une suite proposée par l'agent à la fin de sa réponse (F-192 / SF-192-01).
 *
 * @param title   titre court de l'option (≤ {@link NextStepsBlock#MAX_TITLE_CHARS})
 * @param why     le motif, fondé sur un fait du tour (≤ {@link NextStepsBlock#MAX_WHY_CHARS})
 * @param label   nature du geste : {@code VERIFIER}, {@code CONTINUER}, {@code LIVRER} ou {@code RISQUE}
 * @param request la demande complète qui ira dans la saisie — rien n'est envoyé sans geste
 */
public record NextStep(String title, String why, Label label, String request) {

    /** Les quatre étiquettes, toujours les mêmes, pour lire vite. */
    public enum Label {
        VERIFIER, CONTINUER, LIVRER, RISQUE
    }
}
