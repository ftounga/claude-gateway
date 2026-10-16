package fr.claudegateway.atelier.besoin;

/** Besoin déjà décidé (F-205) : 409. */
public class AppRequestConflictException extends RuntimeException {

    public AppRequestConflictException(String message) {
        super(message);
    }
}
