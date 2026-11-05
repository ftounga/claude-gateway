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
import fr.claudegateway.jarvis.state.extract.HistoryNotesReader;
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
    /** SF-247-17 — l'enveloppe ponctuelle des constats sur l'historique, par passage (défaut 20 €). */
    private final BigDecimal notesEnvelopeEur;
    private final BigDecimal notesMonthlyCapEur;
    static final int MAX_ENVELOPE_EUR = 100;
    private HistoryNotesReader notes;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setNotes(HistoryNotesReader notes) {
        this.notes = notes;
    }

    public JarvisStateSyncService(JarvisStateSyncStore store, StateSyncJobHandler handler,
            JarvisStateRegistry registry, JarvisStateProperties properties, RunnerHostRepository hosts,
            AsyncJobQueue queue, JarvisCostService costs, PlatformTransactionManager transactionManager, Clock clock,
            @Value("${app.jarvis.state.sync-window-days:90}") int windowDays,
            @Value("${app.jarvis.state.sync-notes-envelope-eur:20}") BigDecimal notesEnvelopeEur,
            @Value("${app.jarvis.state.notes-cap-eur:15}") BigDecimal notesMonthlyCapEur) {
        this.notesEnvelopeEur = notesEnvelopeEur == null ? new BigDecimal("20") : notesEnvelopeEur;
        this.notesMonthlyCapEur = notesMonthlyCapEur == null ? new BigDecimal("15") : notesMonthlyCapEur;
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

    /**
     * Une phase, telle que l'écran la montre. SF-247-17 (phase {@code NOTES}) : {@code estimateEur} (estimation au
     * lancement), {@code envelopeEur} (enveloppe ponctuelle du passage), {@code kept} / {@code rejected} (faits retenus
     * et rejetés par la garde de citation), {@code calls} ; 0 pour les autres phases.
     */
    public record PhaseView(String key, String status, int total, int done, int facts, long durationMs,
            BigDecimal costEur, String detail, int read, int missing, BigDecimal estimateEur, BigDecimal envelopeEur,
            int kept, int rejected, int calls) {

        PhaseView(String key, String status, int total, int done, int facts, long durationMs, BigDecimal costEur,
                String detail, int read, int missing) {
            this(key, status, total, done, facts, durationMs, costEur, detail, read, missing, BigDecimal.ZERO,
                    BigDecimal.ZERO, 0, 0, 0);
        }
    }

    /** Un passage, tel que l'écran le montre. */
    public record SyncView(UUID id, UUID hostId, String status, String note, int windowDays, OffsetDateTime startedAt,
            OffsetDateTime finishedAt, List<PhaseView> phases) {
    }

    /** Le résultat d'un lancement : le passage, et s'il vient d'être créé. */
    public record Started(SyncView sync, boolean created) {
    }

    /** Lance (ou rend) le passage d'un client, enveloppe des constats sur l'historique par défaut. */
    public Started start(UUID userId, UUID hostId) {
        return start(userId, hostId, null);
    }

    /**
     * SF-247-17 — l'enveloppe ponctuelle des constats sur l'historique pour un passage : celle demandée (bornée à
     * [0 ; {@value #MAX_ENVELOPE_EUR} €], 0 = pas de constats), sinon le défaut configuré.
     */
    BigDecimal envelope(BigDecimal requested) {
        BigDecimal e = requested == null ? notesEnvelopeEur : requested;
        if (e.signum() < 0) {
            e = BigDecimal.ZERO;
        }
        return e.min(BigDecimal.valueOf(MAX_ENVELOPE_EUR)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** L'enveloppe par défaut d'un passage et le plafond mensuel des constats au fil de l'eau (écran, avant lancement). */
    public record Envelope(BigDecimal notesEnvelopeEur, BigDecimal notesMonthlyCapEur, int maxEnvelopeEur) {
    }

    public Envelope envelopeInfo() {
        return new Envelope(envelope(null), notesMonthlyCapEur, MAX_ENVELOPE_EUR);
    }

    /** Lance (ou rend) le passage d'un client ; {@code envelopeEur} : l'enveloppe des constats (null = défaut). */
    public Started start(UUID userId, UUID hostId, BigDecimal envelopeEur) {
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
        s.setP4Status(SyncRow.P_PENDING);
        s.setP4EnvelopeEur(envelope(envelopeEur));
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
                // SF-247-17 — les constats sur l'historique, juste après l'historique.
                new PhaseView("NOTES", s.getP4Status(), Math.max(s.getP4Total(), s.getP4Done()), s.getP4Done(),
                        s.getP4Facts(), s.getP4Ms(), costs.toEur(s.getP4CostUsd()), s.getP4Detail(), 0, 0,
                        estimateEur(s), s.getP4EnvelopeEur(), s.getP4Kept(), s.getP4Rejected(), s.getP4Calls()),
                new PhaseView("CAPTURES", s.getP2Status(), s.getP2Total(), s.getP2Done(), s.getP2Facts(),
                        s.getP2Ms(), costs.toEur(s.getP2CostUsd()), s.getP2Detail(), s.getP2Read(), s.getP2Missing()),
                new PhaseView("RELEVE", s.getP3Status(), s.getP3Total(), s.getP3Done(), s.getP3Facts(), s.getP3Ms(),
                        BigDecimal.ZERO, s.getP3Detail(), 0, 0));
        return new SyncView(s.getId(), s.getHostId(), s.getStatus(), s.getNote(), s.getWindowDays(),
                s.getStartedAt(), s.getFinishedAt(), phases);
    }

    /** L'estimation des constats sur l'historique, en euros (bornée à l'enveloppe : on ne la dépasse jamais). */
    private BigDecimal estimateEur(SyncRow s) {
        if (notes == null || s.getP4EstChars() <= 0) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal eur = notes.toEur(notes.estimateUsd(s.getP4EstChars(), Math.max(1, s.getP4EstCalls())));
            return eur.setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }
}
