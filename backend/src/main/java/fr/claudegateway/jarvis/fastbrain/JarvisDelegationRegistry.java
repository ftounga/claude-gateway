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

    // ------------------------------------------------------------------ SF-235-04 (D10) : la demande reformulée

    /** Au-delà, une reformulation non prise par un tour ne vaut plus (elle ne doit pas déteindre sur un autre). */
    static final Duration REFORMULATION_TTL = Duration.ofMinutes(2);

    /** Une reformulation qui attend le tour Opus de son fil. */
    public record Reformulation(UUID delegationId, String demande, String label, Instant at, UUID linkId) {

        /** Compatibilité d'avant F-245 / SF-245-03 : sans ligne-lien. */
        public Reformulation(UUID delegationId, String demande, String label, Instant at) {
            this(delegationId, demande, label, at, null);
        }
    }

    private final Map<String, Reformulation> reformulations = new ConcurrentHashMap<>();

    private static String key(UUID userId, UUID subjectId, Integer chantier) {
        return userId + "|" + subjectId + "|" + (chantier == null ? "-" : chantier);
    }

    /**
     * La délégation part vers le fil {@code (sujet, chantier)} : sa demande reformulée attend le tour Opus que
     * l'écran ouvre sur ce fil, qui la joindra à son message (jamais à la consigne système).
     */
    public void offer(UUID userId, UUID subjectId, Integer chantier, UUID delegationId, String demande,
            String label) {
        offer(userId, subjectId, chantier, delegationId, demande, label, null);
    }

    /**
     * F-245 / SF-245-03 — même offre, en portant la ligne-lien du fil Jarvis ({@code linkId}, ou {@code null}) que le
     * tour qui prendra la reformulation terminera.
     */
    public void offer(UUID userId, UUID subjectId, Integer chantier, UUID delegationId, String demande,
            String label, UUID linkId) {
        if (userId == null || subjectId == null || demande == null || demande.isBlank()) {
            return;
        }
        reformulations.put(key(userId, subjectId, chantier),
                new Reformulation(delegationId, demande.strip(), label, clock.instant(), linkId));
    }

    /** Prend (une seule fois) la reformulation fraîche qui attend ce fil du compte, ou {@code null}. */
    public Reformulation take(UUID userId, UUID subjectId, Integer chantier) {
        if (userId == null || subjectId == null) {
            return null;
        }
        Reformulation r = reformulations.remove(key(userId, subjectId, chantier));
        return r != null && !r.at().isBefore(clock.instant().minus(REFORMULATION_TTL)) ? r : null;
    }

    /** Vrai si le fil {@code (sujet, chantier)} du compte a un tour vivant (la délégation deviendra une précision). */
    public boolean live(UUID userId, UUID subjectId, Integer chantier) {
        try {
            return liveTurns != null && userId != null && subjectId != null
                    && liveTurns.find(userId, subjectId, chantier).isPresent();
        } catch (RuntimeException e) {
            return false;
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
