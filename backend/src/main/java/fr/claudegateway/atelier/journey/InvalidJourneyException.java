package fr.claudegateway.atelier.journey;

/** Une demande sur le parcours qui ne peut pas s'appliquer (F-176) — 400, message lisible. */
public class InvalidJourneyException extends RuntimeException {

    public InvalidJourneyException(String message) {
        super(message);
    }
}
