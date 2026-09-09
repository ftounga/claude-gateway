package fr.claudegateway.runner.host;

/**
 * Levée lorsqu'un poste n'existe pas ou n'appartient pas à l'utilisateur courant (F-48 / SF-48-01).
 * Mappée en 404, <b>indiscernable</b> : elle ne révèle jamais l'existence du poste d'autrui.
 */
public class RunnerHostNotFoundException extends RuntimeException {

    public RunnerHostNotFoundException(String message) {
        super(message);
    }
}
