package fr.claudegateway.decks;

/**
 * Le constructeur de deck n'a pas répondu (F-129 / SF-129-05). <b>Distinct d'une description
 * invalide</b> : l'agent peut alors retomber sur {@code python-pptx} <b>s'il est présent</b> sur le
 * poste — sinon il le dit.
 */
public class DeckBuilderUnavailableException extends RuntimeException {

    public DeckBuilderUnavailableException(String message) {
        super(message);
    }
}
