package fr.claudegateway.radar;

/** Le geste ne correspond pas à l'état du sujet (F-99 / SF-99-04) : déjà clos, rien à confirmer… 409. */
public class RadarStateConflictException extends RuntimeException {

    public RadarStateConflictException(String message) {
        super(message);
    }
}
