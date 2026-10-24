package fr.claudegateway.atelier.sends;

/** Le message désigné n'existe pas dans ce terminal, pour ce compte (F-211 / SF-211-01) → 404. */
public class MessageBlockSendNotFoundException extends RuntimeException {

    public MessageBlockSendNotFoundException(String message) {
        super(message);
    }
}
