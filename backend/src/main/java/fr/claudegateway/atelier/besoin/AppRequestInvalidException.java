package fr.claudegateway.atelier.besoin;

/** Besoin invalide (F-205) : 400, ou message d'erreur rendu à l'agent par l'outil. */
public class AppRequestInvalidException extends RuntimeException {

    public AppRequestInvalidException(String message) {
        super(message);
    }
}
