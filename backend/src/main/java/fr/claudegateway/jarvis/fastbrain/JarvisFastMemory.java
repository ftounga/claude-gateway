package fr.claudegateway.jarvis.fastbrain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <b>La mémoire de conversation du cerveau rapide</b> (F-235 / SF-235-02, D6 ; F-239 / SF-239-09) : les échanges <b>de
 * la journée</b> du compte (depuis minuit, heure de Paris), au plus {@value #MAX_EXCHANGES}, <b>mots seuls</b> (ce que
 * le PO a dit, ce que Jarvis a répondu ou annoncé). Clefée par {@code user_id} : un compte ne voit jamais la mémoire
 * d'un autre. En mémoire ; après un redémarrage, elle est <b>réhydratée</b> une fois par jour depuis le fil du compte
 * ({@link Loader}), où les échanges sont aussi tracés (SF-235-04).
 */
@Component
public class JarvisFastMemory {

    static final int MAX_EXCHANGES = 40;
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    /** Un échange : la parole et la réponse dite (ou l'annonce d'une délégation). */
    public record Exchange(Instant at, String said, String answered) {
    }

    /** F-239 / SF-239-09 — relit les échanges du jour d'un compte depuis le fil (sous {@code user_id}). */
    public interface Loader {
        List<Exchange> today(UUID userId, Instant dayStart);
    }

    private final Map<UUID, Deque<Exchange>> byUser = new ConcurrentHashMap<>();
    /** Le jour où chaque compte a été réhydraté (une fois par jour au plus). */
    private final Map<UUID, LocalDate> hydrated = new ConcurrentHashMap<>();
    private final Clock clock;
    private Loader loader;

    public JarvisFastMemory(Clock clock) {
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setLoader(Loader loader) {
        this.loader = loader;
    }

    /** Retient un échange du compte. */
    public void remember(UUID userId, String said, String answered) {
        if (userId == null || said == null || said.isBlank()) {
            return;
        }
        hydrate(userId);
        Deque<Exchange> deque = byUser.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new Exchange(clock.instant(), said.strip(), answered == null ? "" : answered.strip()));
            while (deque.size() > MAX_EXCHANGES) {
                deque.removeFirst();
            }
        }
    }

    /** Les échanges de la journée du compte, du plus ancien au plus récent. */
    public List<Exchange> recall(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        hydrate(userId);
        Deque<Exchange> deque = byUser.get(userId);
        if (deque == null) {
            return List.of();
        }
        Instant from = dayStart();
        synchronized (deque) {
            deque.removeIf(e -> e.at().isBefore(from));
            return new ArrayList<>(deque);
        }
    }

    /** Minuit, heure de Paris, aujourd'hui. */
    Instant dayStart() {
        return LocalDate.ofInstant(clock.instant(), ZONE).atStartOfDay(ZONE).toInstant();
    }

    /** Une fois par jour et par compte : si la mémoire est vide, elle est relue depuis le fil. Best-effort. */
    private void hydrate(UUID userId) {
        if (loader == null) {
            return;
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZONE);
        if (today.equals(hydrated.put(userId, today))) {
            return;
        }
        Deque<Exchange> existing = byUser.get(userId);
        if (existing != null && !existing.isEmpty()) {
            return;
        }
        List<Exchange> loaded;
        try {
            loaded = loader.today(userId, dayStart());
        } catch (RuntimeException e) {
            return;
        }
        if (loaded == null || loaded.isEmpty()) {
            return;
        }
        Deque<Exchange> deque = byUser.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (deque) {
            if (deque.isEmpty()) {
                loaded.stream().skip(Math.max(0, loaded.size() - MAX_EXCHANGES)).forEach(deque::addLast);
            }
        }
    }
}
