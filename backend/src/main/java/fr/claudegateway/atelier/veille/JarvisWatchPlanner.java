package fr.claudegateway.atelier.veille;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.veille.JarvisWatchExceptions.WatchConflictException;
import fr.claudegateway.runner.RunnerLiveness;

import jakarta.annotation.PreDestroy;

/**
 * <b>Le planificateur des veilles</b> (F-230 / SF-230-02, D3/D6/D7) : à chaque passage de la tâche de fond, chaque
 * veille active dont le créneau est dû part — poste en ligne —, ou est notée manquée.
 *
 * <p><b>Réutilise le planificateur des routines</b> sans le confondre avec lui : mêmes créneaux
 * ({@code RoutineSlots}, jours ouvrés), même verrou par {@code UPDATE} conditionnel, même rattrapage unique ; mais
 * une veille lance une <b>commande</b> (la sonde), jamais un tour d'agent, et n'attend pas que le sujet soit
 * libre : elle ne touche ni au fil ni au poste au-delà d'une lecture.</p>
 *
 * <p><b>Isolation à chaque passage</b> : le sujet est relu par {@code findByIdAndUserId}, le poste interrogé sous
 * {@code (userId, hostId)}.</p>
 */
@Service
public class JarvisWatchPlanner {

    /** Veilles examinées par page. */
    static final int PAGE = 200;
    /** Passages simultanés au plus : une sonde dure quelques secondes, une minute au pire. */
    static final int WORKERS = 4;

    private static final Logger log = LoggerFactory.getLogger(JarvisWatchPlanner.class);

    private final JarvisWatchRepository watches;
    private final WorkspaceRepository workspaces;
    private final WorkspaceService workspaceService;
    private final RunnerLiveness liveness;
    private final JarvisWatchPassage passage;
    private final JarvisWatchRunRepository runs;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration catchUpGrace;
    private final ExecutorService pool = Executors.newFixedThreadPool(WORKERS, runnable -> {
        Thread thread = new Thread(runnable, "jarvis-veille");
        thread.setDaemon(true);
        return thread;
    });
    private Executor executor = pool;

    public JarvisWatchPlanner(JarvisWatchRepository watches, WorkspaceRepository workspaces,
            WorkspaceService workspaceService, RunnerLiveness liveness, JarvisWatchPassage passage,
            JarvisWatchRunRepository runs, PlatformTransactionManager transactionManager, Clock clock,
            @Value("${app.jarvis.watches.catch-up-grace:PT10M}") Duration catchUpGrace) {
        this.watches = watches;
        this.workspaces = workspaces;
        this.workspaceService = workspaceService;
        this.liveness = liveness;
        this.passage = passage;
        this.runs = runs;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.catchUpGrace = catchUpGrace;
    }

    /** Tests : exécuter les passages sur le fil appelant. */
    void setExecutor(Executor executor) {
        this.executor = executor;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /** Un passage, maintenant. */
    public int runOnce() {
        return runOnce(OffsetDateTime.now(clock));
    }

    /** Un passage du planificateur ; rend le nombre de sondes lancées. */
    public int runOnce(OffsetDateTime now) {
        int started = 0;
        for (int page = 0;; page++) {
            List<JarvisWatch> rows = watches.findByActiveTrueAndProposedFalseOrderByIdAsc(PageRequest.of(page, PAGE));
            for (JarvisWatch watch : rows) {
                try {
                    if (plan(watch, now)) {
                        started++;
                    }
                } catch (RuntimeException e) {
                    log.warn("Planification de veille en échec (veille={}) : {}", watch.getId(),
                            e.getClass().getSimpleName());
                }
            }
            if (rows.size() < PAGE) {
                return started;
            }
        }
    }

    /** Examine une veille ; vrai si une sonde est partie. */
    boolean plan(JarvisWatch watch, OffsetDateTime now) {
        Optional<OffsetDateTime> dueSlot = JarvisWatchSlots.dueSlot(watch, now);
        if (dueSlot.isEmpty()) {
            return false;
        }
        OffsetDateTime due = dueSlot.get();
        if (watch.getLastSlotAt() != null && !watch.getLastSlotAt().isBefore(due)) {
            return false; // créneau déjà traité
        }
        Optional<Workspace> workspace = workspaces.findByIdAndUserId(watch.getWorkspaceId(), watch.getUserId());
        if (workspace.isEmpty() || !JarvisWatchService.isWatchCapable(workspace.get())) {
            return false; // le sujet n'est plus sur un poste : rien ne part
        }
        if (!liveness.isAlive(watch.getUserId(), workspace.get().getHostId())) {
            recordMissedOnce(watch, due);
            return false; // poste hors ligne : rattrapage à la reconnexion
        }
        boolean missed = watch.getMissedSlotAt() != null && watch.getMissedSlotAt().isEqual(due);
        String trigger = missed || now.isAfter(due.plus(catchUpGrace)) ? JarvisWatchRun.CATCH_UP
                : JarvisWatchRun.SCHEDULED;
        Integer claimed = transactions.execute(status -> watches.claimSlot(watch.getId(), due));
        if (claimed == null || claimed == 0) {
            return false; // un autre pod a pris ce créneau
        }
        try {
            executor.execute(() -> safeRun(watch, workspace.get(), due, trigger));
        } catch (RejectedExecutionException e) {
            log.warn("Veille non lancée : exécuteur arrêté (veille={})", watch.getId());
            return false;
        }
        return true;
    }

    private void safeRun(JarvisWatch watch, Workspace workspace, OffsetDateTime due, String trigger) {
        try {
            passage.run(watch, workspace, due, trigger);
        } catch (RuntimeException e) {
            log.warn("Passage de veille en échec (veille={}) : {}", watch.getId(), e.getClass().getSimpleName());
        }
    }

    /** Note le créneau manqué — une seule ligne par créneau, quel que soit le nombre de passages. */
    private void recordMissedOnce(JarvisWatch watch, OffsetDateTime due) {
        transactions.executeWithoutResult(status -> {
            if (watches.markMissed(watch.getId(), due) == 1) {
                OffsetDateTime now = OffsetDateTime.now(clock);
                runs.save(JarvisWatchRun.builder().userId(watch.getUserId()).workspaceId(watch.getWorkspaceId())
                        .watchId(watch.getId()).slotAt(due).trigger(JarvisWatchRun.SCHEDULED)
                        .status(JarvisWatchRun.MISSED_OFFLINE).errorCode(JarvisWatchProbeRunner.ERR_OFFLINE)
                        .startedAt(now).finishedAt(now).durationMs(0L).build());
            }
        });
    }

    /**
     * « Lancer maintenant » : mêmes gardes qu'à l'heure dite — sujet sur poste, poste en ligne. Le créneau
     * programmé n'est pas consommé. Synchrone : la sonde dure au plus une minute.
     */
    public JarvisWatchRun runNow(UUID userId, UUID workspaceId, UUID watchId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        JarvisWatch watch = watches.findByIdAndUserIdAndWorkspaceId(watchId, userId, workspaceId)
                .orElseThrow(JarvisWatchExceptions.WatchNotFoundException::new);
        if (watch.isProposed()) {
            throw new WatchConflictException("watch_conflict",
                    "Cette veille est une proposition : testez-la puis activez-la d'abord.");
        }
        if (!JarvisWatchService.isWatchCapable(workspace)) {
            throw new JarvisWatchExceptions.WatchUnsupportedException(
                    "Une veille tourne sur le poste d'un sujet : ce terminal ne peut pas en porter.");
        }
        if (!liveness.isAlive(userId, workspace.getHostId())) {
            throw new WatchConflictException("watch_host_offline",
                    "Le poste est hors ligne : la veille ne peut pas passer maintenant.");
        }
        return passage.run(watch, workspace, null, JarvisWatchRun.MANUAL);
    }
}
