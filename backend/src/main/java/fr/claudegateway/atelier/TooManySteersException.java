package fr.claudegateway.atelier;

/**
 * Trop de précisions déposées pour un même tour (F-39 / SF-39-19).
 *
 * <p>Au-delà de quelques-unes, ce n'est plus une précision : c'est un nouveau tour qu'il faut, avec
 * une consigne claire, plutôt qu'une succession de rustines que l'agent lira toutes ensemble.</p>
 *
 * <p>Mappé en {@code 409 too_many_steers} — un conflit avec l'état du tour, pas une panne.</p>
 */
public class TooManySteersException extends RuntimeException {

    public TooManySteersException(String message) {
        super(message);
    }
}
