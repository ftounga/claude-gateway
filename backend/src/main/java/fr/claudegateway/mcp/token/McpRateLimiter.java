package fr.claudegateway.mcp.token;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Limite par jeton personnel : <b>60 appels par minute</b> (cadrage F-112 §6.5). Fenêtre glissante
 * en mémoire, par pod (une limite partagée multi-pod est un suivi documenté). Au-delà, l'appel est
 * refusé (429).
 */
@Component
public class McpRateLimiter {

    static final int MAX_PER_MINUTE = 60;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * Nombre de jetons suivis au-delà duquel les fenêtres périmées sont purgées (F-117 / SF-117-04).
     * Sans lui, la carte grossirait indéfiniment sur un pod de longue vie : un jeton vu une fois y
     * gardait une entrée pour toujours. Même parade que {@code HelpRateLimiter.evictStale}.
     */
    private static final int CLEANUP_THRESHOLD = 1_000;

    private final Map<UUID, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    /** Constructeur d'injection Spring : horloge système. */
    public McpRateLimiter() {
        this(Clock.systemUTC());
    }

    /** Constructeur de test (F-117 / SF-117-04) : horloge contrôlée pour vérifier l'éviction. */
    McpRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @return {@code true} si l'appel est autorisé, {@code false} si la limite est atteinte. */
    public boolean tryAcquire(UUID tokenId) {
        Instant now = Instant.now(clock);
        Instant threshold = now.minus(WINDOW);
        if (hits.size() > CLEANUP_THRESHOLD) {
            evictStale(threshold);
        }
        Deque<Instant> window = hits.computeIfAbsent(tokenId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(threshold)) {
                window.pollFirst();
            }
            if (window.size() >= MAX_PER_MINUTE) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /**
     * Purge les jetons dont la fenêtre ne contient plus aucun appel récent (F-117 / SF-117-04). Copie
     * du motif de {@code HelpRateLimiter.evictStale} : on ne verrouille QUE l'entrée d'un jeton à la
     * fois, deux jetons ne se bloquent jamais, et aucun appel ne peut se perdre entre une purge et un
     * enregistrement (la fenêtre est vidée puis retirée seulement si elle est vide).
     */
    private void evictStale(Instant threshold) {
        for (UUID tokenId : Map.copyOf(hits).keySet()) {
            hits.computeIfPresent(tokenId, (key, window) -> {
                synchronized (window) {
                    while (!window.isEmpty() && window.peekFirst().isBefore(threshold)) {
                        window.pollFirst();
                    }
                    return window.isEmpty() ? null : window;
                }
            });
        }
    }

    /** Nombre de jetons actuellement suivis (test de l'éviction, F-117 / SF-117-04). */
    int trackedTokens() {
        return hits.size();
    }
}
