package fr.claudegateway.atelier.besoin;

/** Besoin introuvable pour cet utilisateur (F-205) : 404. */
public class AppRequestNotFoundException extends RuntimeException {

    public AppRequestNotFoundException(String message) {
        super(message);
    }
}
