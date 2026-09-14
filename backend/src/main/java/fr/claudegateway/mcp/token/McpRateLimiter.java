package fr.claudegateway.mcp.token;

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

    private final Map<UUID, Deque<Instant>> hits = new ConcurrentHashMap<>();

    /** @return {@code true} si l'appel est autorisé, {@code false} si la limite est atteinte. */
    public boolean tryAcquire(UUID tokenId) {
        Instant now = Instant.now();
        Instant threshold = now.minus(WINDOW);
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
}
