package fr.claudegateway.runner;

import java.time.Duration;

/**
 * Backoff exponentiel plafonné pour la reconnexion du runner (F-38 / SF-38-03). Part d'un délai
 * initial, double à chaque échec, sans dépasser un maximum. {@link #reset()} après une connexion
 * réussie. Sans état partagé : logique pure, testable unitairement.
 */
public final class Backoff {

    private final Duration initial;
    private final Duration max;
    private Duration current;

    public Backoff(Duration initial, Duration max) {
        if (initial.isZero() || initial.isNegative()) {
            throw new IllegalArgumentException("initial doit être positif");
        }
        if (max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("max doit être >= initial");
        }
        this.initial = initial;
        this.max = max;
        this.current = initial;
    }

    /** Délai courant à attendre avant la prochaine tentative, puis double (plafonné). */
    public Duration nextDelay() {
        Duration delay = current;
        Duration doubled = current.multipliedBy(2);
        current = doubled.compareTo(max) > 0 ? max : doubled;
        return delay;
    }

    /** Remet le backoff à sa valeur initiale (après une connexion réussie). */
    public void reset() {
        this.current = initial;
    }
}
