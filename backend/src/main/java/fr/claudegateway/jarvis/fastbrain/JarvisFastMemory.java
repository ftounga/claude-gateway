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

/**
 * <b>La mémoire de conversation du cerveau rapide</b> (F-235 / SF-235-02, D6) : les échanges des 30 dernières minutes
 * du compte, au plus 12, <b>mots seuls</b> (ce que le PO a dit, ce que Jarvis a répondu ou annoncé). Clefée par
 * {@code user_id} : un compte ne voit jamais la mémoire d'un autre. En mémoire seulement : les échanges sont aussi
 * tracés dans le fil (SF-235-04).
 */
@Component
public class JarvisFastMemory {

    static final int MAX_EXCHANGES = 12;
    static final Duration WINDOW = Duration.ofMinutes(30);

    /** Un échange : la parole et la réponse dite (ou l'annonce d'une délégation). */
    public record Exchange(Instant at, String said, String answered) {
    }

    private final Map<UUID, Deque<Exchange>> byUser = new ConcurrentHashMap<>();
    private final Clock clock;

    public JarvisFastMemory(Clock clock) {
        this.clock = clock;
    }

    /** Retient un échange du compte. */
    public void remember(UUID userId, String said, String answered) {
        if (userId == null || said == null || said.isBlank()) {
            return;
        }
        Deque<Exchange> deque = byUser.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new Exchange(clock.instant(), said.strip(), answered == null ? "" : answered.strip()));
            while (deque.size() > MAX_EXCHANGES) {
                deque.removeFirst();
            }
        }
    }

    /** Les échanges encore valables du compte, du plus ancien au plus récent. */
    public List<Exchange> recall(UUID userId) {
        Deque<Exchange> deque = userId == null ? null : byUser.get(userId);
        if (deque == null) {
            return List.of();
        }
        Instant from = clock.instant().minus(WINDOW);
        synchronized (deque) {
            deque.removeIf(e -> e.at().isBefore(from));
            return new ArrayList<>(deque);
        }
    }
}
