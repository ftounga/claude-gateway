package fr.claudegateway.atelier.plume;

/** Le fournisseur a répondu, mais sans rien d'exploitable (F-193 / SF-193-01). */
public class PlumeEmptyOutputException extends RuntimeException {

    public PlumeEmptyOutputException() {
        super("Réponse d'enrichissement vide");
    }
}
