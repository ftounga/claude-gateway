package fr.claudegateway.jarvis.companion.core;

/**
 * Attente croissante entre deux essais quand la gateway ne répond pas (F-232 / SF-232-03) : 5 s, 10 s, 20 s… au
 * plus 5 minutes ; une réponse remet à zéro. Pur.
 */
public final class Backoff {

    public static final long FIRST_MS = 5_000;
    public static final long MAX_MS = 5 * 60_000;

    private long next = FIRST_MS;

    /** Le délai avant le prochain essai, puis le double (borné). */
    public long next() {
        long delay = next;
        next = Math.min(MAX_MS, next * 2);
        return delay;
    }

    public void reset() {
        next = FIRST_MS;
    }
}
