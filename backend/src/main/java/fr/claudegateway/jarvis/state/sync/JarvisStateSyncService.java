package fr.claudegateway.jarvis.state.sync;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.jarvis.JarvisCostService;
import fr.claudegateway.jarvis.state.JarvisStateProperties;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.capture.CaptureReadJobHandler;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.shared.jobs.AsyncJobQueue;

/**
 * <b>« Synchroniser »</b> (F-247 / SF-247-16) : lancer, suivre et annuler le passage d'un client. Tout est sous le
 * {@code user_id} du principal ; un client d'un autre compte n'existe pas. <b>Idempotent</b> : un seul passage actif
 * par client ; relancer pendant qu'il travaille le rend tel quel (et relance sa chaîne si elle s'est perdue).
 */
@Service
public class JarvisStateSyncService {

    /** Au-delà, une chaîne qui ne bouge plus est réputée perdue et relancée au geste suivant. */
    static final Duration STALLED = Duration.ofMinutes(10);
    static final Duration RECENT = Duration.ofDays(7);

    private final JarvisStateSyncStore store;
    private final StateSyncJobHandler handler;
    private final JarvisStateRegistry registry;
    private final JarvisStateProperties properties;
    private final RunnerHostRepository hosts;
    private final AsyncJobQueue queue;
    private final JarvisCostService costs;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int windowDays;

    public JarvisStateSyncService(JarvisStateSyncStore store, StateSyncJobHandler handler,
            JarvisStateRegistry registry, JarvisStateProperties properties, RunnerHostRepository hosts,
            AsyncJobQueue queue, JarvisCostService costs, PlatformTransactionManager transactionManager, Clock clock,
            @Value("${app.jarvis.state.sync-window-days:90}") int windowDays) {
        this.store = store;
        this.handler = handler;
        this.registry = registry;
        this.properties = properties;
        this.hosts = hosts;
        this.queue = queue;
        this.costs = costs;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.windowDays = Math.max(1, Math.min(365, windowDays));
    }

    /** Client hors compte. */
    public static class NotFound extends RuntimeException {
        public NotFound() {
            super("not_found");
        }
    }

    /** Refus dit à l'écran (registre coupé, coupe-circuit). */
    public static class Refused extends RuntimeException {
        private final String code;

        public Refused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Une phase, telle que l'écran la montre. */
    public record PhaseView(String key, String status, int total, int done, int facts, long durationMs,
            BigDecimal costEur, String detail, int read, int missing) {
    }

    /** Un passage, tel que l'écran le montre. */
    public record SyncView(UUID id, UUID hostId, String status, String note, int windowDays, OffsetDateTime startedAt,
            OffsetDateTime finishedAt, List<PhaseView> phases) {
    }

    /** Le résultat d'un lancement : le passage, et s'il vient d'être créé. */
    public record Started(SyncView sync, boolean created) {
    }

    /** Lance (ou rend) le passage d'un client. */
    public Started start(UUID userId, UUID hostId) {
        requireOwned(userId, hostId);
        if (!handler.enabled() || !properties.enabled()) {
            throw new Refused("sync_off", "La synchronisation est coupée pour l'instant.");
        }
        if (!registry.settingsOf(userId, hostId).isEnabled()) {
            throw new Refused("registry_off", "Le registre est coupé pour ce client : rallumez-le d'abord.");
        }
        Optional<SyncRow> active = store.findActive(userId, hostId);
        if (active.isPresent()) {
            revive(active.get());
            return new Started(view(active.get()), false);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        SyncRow s = new SyncRow();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setHostId(hostId);
        s.setStatus(SyncRow.RUNNING);
        s.setPhase(1);
        s.setStep(0);
        s.setWindowDays(windowDays);
        s.setP1Status(SyncRow.P_PENDING);
        s.setP2Status(SyncRow.P_PENDING);
        s.setP3Status(SyncRow.P_PENDING);
        s.setStartedAt(now);
        s.setUpdatedAt(now);
        try {
            tx.executeWithoutResult(t -> {
                store.insert(s);
                handler.schedule(userId, s.getId(), 0, null);
            });
        } catch (DuplicateKeyException race) {
            return store.findActive(userId, hostId).map(x -> new Started(view(x), false))
                    .orElseThrow(() -> race);
        }
        return new Started(view(s), true);
    }

    /** Une chaîne qui ne bouge plus depuis 10 min (travail perdu) repart à une nouvelle étape. */
    void revive(SyncRow s) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (s.getUpdatedAt() != null && s.getUpdatedAt().isAfter(now.minus(STALLED))) {
            return;
        }
        tx.executeWithoutResult(t -> {
            int step = store.bumpStep(s.getUserId(), s.getId(), s.getStep(), now);
            if (step >= 0) {
                handler.schedule(s.getUserId(), s.getId(), step, null);
            }
        });
    }

    /** Annule le passage actif d'un client ; les lectures de captures encore en attente sont abandonnées. */
    public SyncView cancel(UUID userId, UUID hostId) {
        requireOwned(userId, hostId);
        UUID id = store.cancel(userId, hostId, OffsetDateTime.now(clock)).orElseThrow(NotFound::new);
        queue.dropPending(userId, CaptureReadJobHandler.KIND, StateSyncJobHandler.marker(id), "sync_cancelled");
        return store.find(userId, id).map(this::view).orElseThrow(NotFound::new);
    }

    /** Le dernier passage (7 j) de chaque client du compte. */
    public List<SyncView> latest(UUID userId) {
        Map<UUID, SyncView> perHost = new LinkedHashMap<>();
        for (SyncRow s : store.recent(userId, OffsetDateTime.now(clock).minus(RECENT), 200)) {
            perHost.putIfAbsent(s.getHostId(), view(s));
        }
        return new ArrayList<>(perHost.values());
    }

    private void requireOwned(UUID userId, UUID hostId) {
        if (userId == null || hostId == null || hosts.findByIdAndUserId(hostId, userId).isEmpty()) {
            throw new NotFound();
        }
    }

    SyncView view(SyncRow s) {
        List<PhaseView> phases = List.of(
                new PhaseView("HISTORY", s.getP1Status(), s.getP1Total(), s.getP1Done(), s.getP1Facts(), s.getP1Ms(),
                        BigDecimal.ZERO, s.getP1Detail(), 0, 0),
                new PhaseView("CAPTURES", s.getP2Status(), s.getP2Total(), s.getP2Done(), s.getP2Facts(),
                        s.getP2Ms(), costs.toEur(s.getP2CostUsd()), s.getP2Detail(), s.getP2Read(), s.getP2Missing()),
                new PhaseView("RELEVE", s.getP3Status(), s.getP3Total(), s.getP3Done(), s.getP3Facts(), s.getP3Ms(),
                        BigDecimal.ZERO, s.getP3Detail(), 0, 0));
        return new SyncView(s.getId(), s.getHostId(), s.getStatus(), s.getNote(), s.getWindowDays(),
                s.getStartedAt(), s.getFinishedAt(), phases);
    }
}
