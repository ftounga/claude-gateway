package fr.claudegateway.jarvis.fastbrain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.live.LiveTurnRegistry;

/**
 * <b>Les délégations en cours</b> (F-235 / SF-235-02, D5d) : ce que le cerveau rapide a confié à Opus, pour qu'il
 * sache dire « je regarde encore les tickets d'Edenred ». Une délégation est « en cours » tant que son fil a un tour
 * vivant, ou pendant 2 min après son lancement (le tour n'a peut-être pas encore démarré). Clefée par
 * {@code user_id}. En mémoire, comme l'état vivant des tours.
 */
@Component
public class JarvisDelegationRegistry {

    static final int MAX_PER_USER = 8;
    static final Duration GRACE = Duration.ofMinutes(2);
    static final Duration MAX_AGE = Duration.ofHours(2);

    /** Une délégation lancée. */
    public record Running(UUID delegationId, UUID subjectId, String subjectName, Integer chantier, String label,
            Instant since) {
    }

    private final Map<UUID, Deque<Running>> byUser = new ConcurrentHashMap<>();
    private final Clock clock;
    private final LiveTurnRegistry liveTurns;

    public JarvisDelegationRegistry(Clock clock, LiveTurnRegistry liveTurns) {
        this.clock = clock;
        this.liveTurns = liveTurns;
    }

    /** Une délégation vient d'être lancée. */
    public void started(UUID userId, UUID delegationId, UUID subjectId, String subjectName, Integer chantier,
            String label) {
        if (userId == null || subjectId == null) {
            return;
        }
        Deque<Running> deque = byUser.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new Running(delegationId, subjectId, subjectName, chantier, label, clock.instant()));
            while (deque.size() > MAX_PER_USER) {
                deque.removeFirst();
            }
        }
    }

    /** Les délégations encore en cours du compte, de la plus ancienne à la plus récente. */
    public List<Running> running(UUID userId) {
        Deque<Running> deque = userId == null ? null : byUser.get(userId);
        if (deque == null) {
            return List.of();
        }
        Instant now = clock.instant();
        synchronized (deque) {
            deque.removeIf(r -> r.since().isBefore(now.minus(MAX_AGE)) || (r.since().isBefore(now.minus(GRACE))
                    && !alive(userId, r)));
            return new ArrayList<>(deque);
        }
    }

    private boolean alive(UUID userId, Running r) {
        try {
            return liveTurns != null && liveTurns.find(userId, r.subjectId(), r.chantier()).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
