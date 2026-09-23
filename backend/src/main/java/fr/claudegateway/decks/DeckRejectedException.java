package fr.claudegateway.decks;

/** La description du deck est invalide, et <b>on sait pourquoi</b> (F-129 / SF-129-05). */
public class DeckRejectedException extends RuntimeException {

    public DeckRejectedException(String message) {
        super(message);
    }
}
