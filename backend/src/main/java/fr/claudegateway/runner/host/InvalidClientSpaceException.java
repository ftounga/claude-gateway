package fr.claudegateway.runner.host;

/** Un espace inconnu a été demandé (F-106 / SF-106-01) : 400. */
public class InvalidClientSpaceException extends RuntimeException {

    public InvalidClientSpaceException(String raw) {
        super("Espace inconnu : attendu FORGE ou VIGIE.");
    }
}
