package fr.claudegateway.jarvis.state.releve;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.veille.JarvisWatch;
import fr.claudegateway.atelier.veille.JarvisWatchPassage;
import fr.claudegateway.atelier.veille.JarvisWatchProbeRunner;
import fr.claudegateway.atelier.veille.JarvisWatchRepository;
import fr.claudegateway.atelier.veille.JarvisWatchRun;
import fr.claudegateway.jarvis.state.JarvisStateProperties;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateSettings;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.host.RunnerHostService;

import jakarta.annotation.PreDestroy;

/**
 * <b>Le relevé avant la veille du poste</b> (F-247 / SF-247-09, cadrage §4.3, D6) — deux déclencheurs en plus de l'heure :
 * <ul>
 * <li><b>l'inactivité</b> : aucune commande hors relevés depuis 20 min, quelque chose s'est passé depuis le dernier
 * relevé, et celui-ci date de plus de 30 min ⇒ un relevé (un seul pod le prend) ;</li>
 * <li><b>le départ propre</b> ({@code POST /runner/departure}) : si un relevé est dû (plus de 15 min), les sondes du
 * poste passent, les plus anciennes d'abord, dans un plafond de <b>8 s</b> ; l'arrêt n'attend jamais davantage.</li>
 * </ul>
 * Une extinction brutale ne peut pas être captée : Jarvis dit l'âge du dernier relevé.
 */
@Component
public class JarvisReleveTriggers {

    private static final Logger log = LoggerFactory.getLogger(JarvisReleveTriggers.class);
    static final Duration IDLE_AFTER = Duration.ofMinutes(20);
    static final Duration IDLE_MIN_GAP = Duration.ofMinutes(30);
    static final Duration DEPARTURE_DUE = Duration.ofMinutes(15);
    /** Le budget côté passerelle, sous le plafond de 8 s du runner. */
    static final Duration DEPARTURE_BUDGET = Duration.ofMillis(7_500);
    static final long MIN_PROBE_MS = 1_500;

    private final JarvisStateProperties properties;
    private final JarvisStateRegistry registry;
    private final RunnerHostService hosts;
    private final RunnerLiveness liveness;
    private final LiveTurnRegistry liveTurns;
    private final JarvisReleveService releve;
    private final JarvisWatchRepository watches;
    private final WorkspaceRepository workspaces;
    private final JarvisWatchPassage passage;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ExecutorService idlePool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jarvis-releve-idle");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    public JarvisReleveTriggers(JarvisStateProperties properties, JarvisStateRegistry registry, RunnerHostService hosts,
            RunnerLiveness liveness, LiveTurnRegistry liveTurns, JarvisReleveService releve,
            JarvisWatchRepository watches, WorkspaceRepository workspaces, JarvisWatchPassage passage,
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.properties = properties;
        this.registry = registry;
        this.hosts = hosts;
        this.liveness = liveness;
        this.liveTurns = liveTurns;
        this.releve = releve;
        this.watches = watches;
        this.workspaces = workspaces;
        this.passage = passage;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @PreDestroy
    void stop() {
        idlePool.shutdownNow();
    }

    /** Une sonde de relevé active et son sujet. */
    record Probe(JarvisWatch watch, Workspace workspace) {
    }

    /** Les sondes de relevé actives d'un poste, les moins récemment passées d'abord. */
    List<Probe> probesOf(UUID userId, UUID hostId) {
        List<Probe> out = new ArrayList<>();
        for (Workspace w : workspaces.findByUserIdAndHostId(userId, hostId)) {
            if (!userId.equals(w.getUserId())) {
                continue;
            }
            for (JarvisWatch watch : watches.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, w.getId())) {
                if (watch.isState() && watch.isScheduled()) {
                    out.add(new Probe(watch, w));
                }
            }
        }
        out.sort(Comparator.comparing((Probe p) -> p.watch().getLastRunAt(),
                Comparator.nullsFirst(Comparator.naturalOrder())));
        return out;
    }

    /** Le relevé est-il permis pour ce poste (sans la fenêtre de calme : c'est l'appelant qui la choisit) ? */
    boolean allowed(UUID userId, UUID hostId) {
        if (!properties.releve()) {
            return false;
        }
        JarvisStateSettings s = registry.settingsOf(userId, hostId);
        return s.isEnabled() && s.isReleveEnabled()
                && hosts.declaredCapabilities(hostId).contains(JarvisReleveService.LOW_PRIORITY);
    }

    // ------------------------------------------------------------------ départ

    /**
     * Le relevé de départ : rend le nombre de sondes passées. Jamais plus long que {@link #DEPARTURE_BUDGET}
     * (la dernière sonde a son délai réduit au temps restant). Ne lève jamais.
     */
    public int departure(UUID userId, UUID hostId) {
        long deadline = System.nanoTime() + DEPARTURE_BUDGET.toNanos();
        try {
            if (userId == null || hostId == null || !allowed(userId, hostId)) {
                return 0;
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            List<Probe> probes = probesOf(userId, hostId);
            if (probes.isEmpty() || !due(probes, now, DEPARTURE_DUE) || turnLive(userId, probes)
                    || releve.disarmed(userId, hostId, now)) {
                return 0;
            }
            int ran = 0;
            for (Probe p : probes) {
                long left = (deadline - System.nanoTime()) / 1_000_000;
                if (left < MIN_PROBE_MS) {
                    break;
                }
                JarvisWatchRun run = passage.run(p.watch(), p.workspace(), null, JarvisWatchRun.DEPARTURE,
                        Math.min(left, JarvisWatchProbeRunner.RELEVE_TIMEOUT_MS));
                ran++;
                if (JarvisWatchRun.YIELDED.equals(run.getStatus())) {
                    break; // le poste travaille encore : on n'insiste pas
                }
            }
            return ran;
        } catch (RuntimeException e) {
            log.debug("Relevé de départ interrompu ({})", e.getClass().getSimpleName());
            return 0;
        }
    }

    // -------------------------------------------------------------- inactivité

    /** Toutes les 5 min : les postes inactifs depuis 20 min, avec du neuf depuis leur dernier relevé. */
    @Scheduled(fixedDelayString = "${app.jarvis.state.idle-poll-ms:300000}",
            initialDelayString = "${app.jarvis.state.idle-initial-delay-ms:120000}")
    public void idleTick() {
        if (!properties.releve()) {
            return;
        }
        try {
            List<Object[]> pairs = jdbc.query("SELECT DISTINCT w.user_id, ws.host_id FROM jarvis_watches w JOIN workspaces "
                    + "ws ON ws.id = w.workspace_id AND ws.user_id = w.user_id WHERE w.purpose = 'STATE' AND w.active "
                    + "= TRUE AND w.proposed = FALSE AND ws.host_id IS NOT NULL",
                    (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)});
            OffsetDateTime now = OffsetDateTime.now(clock);
            for (Object[] pair : pairs) {
                UUID userId = (UUID) pair[0];
                UUID hostId = (UUID) pair[1];
                try {
                    idle(userId, hostId, now);
                } catch (RuntimeException e) {
                    log.debug("Relevé d'inactivité : poste ignoré ({})", e.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Relevé d'inactivité en échec ({})", e.getClass().getSimpleName());
        }
    }

    /** Lance le relevé d'inactivité d'un poste si les conditions sont réunies ; vrai s'il est parti. */
    boolean idle(UUID userId, UUID hostId, OffsetDateTime now) {
        if (!allowed(userId, hostId) || !liveness.isAlive(userId, hostId)) {
            return false;
        }
        List<Probe> probes = probesOf(userId, hostId);
        if (probes.isEmpty() || turnLive(userId, probes) || releve.disarmed(userId, hostId, now)) {
            return false;
        }
        OffsetDateTime lastActivity = lastActivity(userId, hostId);
        OffsetDateTime lastReleve = lastRun(probes);
        if (lastActivity == null || lastActivity.isAfter(now.minus(IDLE_AFTER))) {
            return false; // jamais actif, ou actif il y a moins de 20 min
        }
        if (lastReleve != null && (!lastActivity.isAfter(lastReleve) || lastReleve.isAfter(now.minus(IDLE_MIN_GAP)))) {
            return false; // rien de neuf depuis le dernier relevé, ou relevé récent
        }
        // Un seul pod prend ce relevé : la première sonde est réservée par un UPDATE conditionnel.
        Integer won = tx.execute(s -> watches.claimRun(probes.get(0).watch().getId(), now,
                now.minus(IDLE_MIN_GAP)));
        if (won == null || won == 0) {
            return false;
        }
        idlePool.execute(() -> {
            for (Probe p : probes) {
                JarvisWatchRun run = passage.run(p.watch(), p.workspace(), null, JarvisWatchRun.IDLE);
                if (JarvisWatchRun.YIELDED.equals(run.getStatus())) {
                    break;
                }
            }
        });
        return true;
    }

    private boolean turnLive(UUID userId, List<Probe> probes) {
        java.util.Set<UUID> subjects = new java.util.HashSet<>();
        probes.forEach(p -> subjects.add(p.workspace().getId()));
        return liveTurns.liveTurnsOf(userId).stream().anyMatch(t -> subjects.contains(t.workspaceId()));
    }

    private static boolean due(List<Probe> probes, OffsetDateTime now, Duration gap) {
        OffsetDateTime last = lastRun(probes);
        return last == null || last.isBefore(now.minus(gap));
    }

    private static OffsetDateTime lastRun(List<Probe> probes) {
        return probes.stream().map(p -> p.watch().getLastRunAt()).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    /** La dernière commande hors relevés et veilles sur les sujets du poste (index du journal du runner). */
    OffsetDateTime lastActivity(UUID userId, UUID hostId) {
        Timestamp t = jdbc.queryForObject("SELECT max(a.created_at) FROM runner_audit a WHERE a.user_id = ? AND "
                + "a.workspace_id IN (SELECT w.id FROM workspaces w WHERE w.user_id = ? AND w.host_id = ?) AND "
                + "a.tool NOT LIKE 'veille_sonde%' AND a.tool NOT LIKE 'releve_sonde%'", Timestamp.class, userId,
                userId, hostId);
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
