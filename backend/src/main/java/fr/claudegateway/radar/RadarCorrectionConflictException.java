package fr.claudegateway.radar;

/** Une annulation impossible (F-99 / SF-99-02) : déjà annulée, ou recouverte par une correction plus récente. 409. */
public class RadarCorrectionConflictException extends RuntimeException {

    public RadarCorrectionConflictException(String message) {
        super(message);
    }
}
