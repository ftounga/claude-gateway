package fr.claudegateway.atelier.sends;

/** Le bloc désigné n'existe pas dans le message (F-211 / SF-211-01) → 400. */
public class InvalidMessageBlockSendException extends RuntimeException {

    public InvalidMessageBlockSendException(String message) {
        super(message);
    }
}
