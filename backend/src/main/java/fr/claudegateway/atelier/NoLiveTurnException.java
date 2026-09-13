package fr.claudegateway.atelier;

/**
 * Une précision arrive alors qu'aucun tour ne tourne plus sur ce projet — ni ici, ni chez un pair
 * (F-84 / SF-84-06) : le tour vient de rendre sa réponse, ou il n'y en a jamais eu.
 *
 * <p>Mappé en {@code 409 no_live_turn} — un conflit avec l'état du tour, pas une panne. L'écran
 * envoie alors le même texte comme un message ordinaire.</p>
 */
public class NoLiveTurnException extends RuntimeException {

    public NoLiveTurnException(String message) {
        super(message);
    }
}
