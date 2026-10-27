package fr.claudegateway.atelier.autonomy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.AtelierChatService;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.journey.ChantierAutonomyLauncher;
import fr.claudegateway.atelier.journey.ChantierPreflight;
import fr.claudegateway.atelier.journey.ChantierPreflightService;
import fr.claudegateway.atelier.journey.ChantierPreflightService.ChantierReadiness;
import fr.claudegateway.atelier.journey.InvalidJourneyException;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyService;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.quota.UsageTurnRepository;
import fr.claudegateway.runner.RunnerLiveness;

/**
 * <b>Lancer un chantier en autonome, et reprendre la main</b> (F-219 / SF-219-02).
 *
 * <p><b>Gardes de départ</b>, dans l'ordre : préflight vert (SF-219-01) ; un chantier autonome par sujet
 * (aucune course en cours, aucun tour vivant) ; au plus {@code app.autonomy.max-parallel} courses en cours par
 * compte — garde-fou de dépense, le chiffre de F-70 ; poste du sujet en ligne.</p>
 *
 * <p><b>Isolation</b> : {@code requireOwned(userId, workspaceId)} d'abord (404 sur le terminal d'autrui) ;
 * toute lecture du journal filtre par {@code user_id}.</p>
 */
@Service
public class AutonomyService implements ChantierAutonomyLauncher {

    private static final Logger log = LoggerFactory.getLogger(AutonomyService.class);

    /** Fenêtre de l'estimation : le coût moyen d'un tour sur ces jours. */
    static final int ESTIMATE_DAYS = 30;

    private final WorkspaceService workspaceService;
    private final ChantierPreflightService preflights;
    private final SubjectJourneyService journeys;
    private final LiveTurnRegistry liveTurns;
    private final AutonomyRunStore store;
    private final AutonomyTurnRunner runner;
    private final AtelierChatService chatService;
    private final Clock clock;
    private final int maxParallel;
    private final Duration staleAfter;

    /** Les départs programmés depuis la conversation (SF-219-04), par sujet — mémoire du pod qui porte le tour. */
    private final Map<String, Deferred> deferred = new java.util.concurrent.ConcurrentHashMap<>();
    private Duration deferredTtl = Duration.ofMinutes(10);

    /** Un départ programmé. */
    record Deferred(UUID userId, UUID workspaceId, int number, OffsetDateTime expiresAt) {
    }

    private RunnerLiveness liveness;
    private UsageTurnRepository usageTurns;
    private AutonomyNotifier notifier;

    public AutonomyService(WorkspaceService workspaceService, ChantierPreflightService preflights,
            SubjectJourneyService journeys, LiveTurnRegistry liveTurns, AutonomyRunStore store,
            AutonomyTurnRunner runner, AtelierChatService chatService, Clock clock,
            @Value("${app.autonomy.max-parallel:4}") int maxParallel,
            @Value("${app.autonomy.stale-after:PT3H}") Duration staleAfter) {
        this.workspaceService = workspaceService;
        this.preflights = preflights;
        this.journeys = journeys;
        this.liveTurns = liveTurns;
        this.store = store;
        this.runner = runner;
        this.chatService = chatService;
        this.clock = clock;
        this.maxParallel = Math.max(1, maxParallel);
        this.staleAfter = staleAfter;
    }

    @Autowired(required = false)
    public void setLiveness(RunnerLiveness liveness) {
        this.liveness = liveness;
    }

    @Autowired(required = false)
    public void setUsageTurns(UsageTurnRepository usageTurns) {
        this.usageTurns = usageTurns;
    }

    @Autowired(required = false)
    public void setNotifier(AutonomyNotifier notifier) {
        this.notifier = notifier;
    }

    public int maxParallel() {
        return maxParallel;
    }

    @Autowired(required = false)
    public void setDeferredTtl(@Value("${app.autonomy.deferred-ttl:PT10M}") Duration deferredTtl) {
        this.deferredTtl = deferredTtl;
    }

    // ------------------------------------------------------------------ vues

    /** Une course telle que l'écran et F-220 la lisent. Jamais d'identifiant de compte. */
    public record RunView(UUID id, UUID workspaceId, int chantierNumber, String chantierTitle, String status,
                          int turns, int stepsDone, int stepsTotal, BigDecimal costUsd, String stopReason,
                          String errorCode, OffsetDateTime startedAt, OffsetDateTime finishedAt) {

        static RunView of(ChantierAutonomyRun r) {
            return new RunView(r.getId(), r.getWorkspaceId(), r.getChantierNumber(), r.getChantierTitle(),
                    r.getStatus().name(), r.getTurns(), r.getStepsDone(), r.getStepsTotal(),
                    r.getCostUsd() == null ? BigDecimal.ZERO : r.getCostUsd().setScale(2, RoundingMode.HALF_UP),
                    r.getStopReason(), r.getErrorCode(), r.getStartedAt(), r.getFinishedAt());
        }
    }

    /**
     * L'estimation d'une course, affichée au lancement (pas de confirmation).
     *
     * @param usd   coût estimé, en dollars
     * @param turns tours estimés
     * @param basis d'où vient le chiffre, en clair
     */
    public record Estimate(BigDecimal usd, int turns, String basis) {
    }

    /** Un chantier ouvert : son préflight, sa course en cours ou la dernière, son estimation. */
    public record BoardEntry(int number, String title, ChantierPreflight preflight, RunView run, Estimate estimate) {
    }

    /**
     * Ce que l'écran lit de l'autonomie d'un sujet.
     *
     * @param running     courses en cours sur le compte
     * @param maxParallel courses en cours au plus sur le compte
     */
    public record Board(List<BoardEntry> chantiers, long running, int maxParallel) {
    }

    // ------------------------------------------------------------------ lecture

    /** Les chantiers ouverts du sujet, chacun avec préflight, course et estimation. 404 sur le terminal d'autrui. */
    public Board board(UUID userId, UUID workspaceId) {
        List<ChantierReadiness> readiness = preflights.all(userId, workspaceId); // requireOwned d'abord
        Map<Integer, ChantierAutonomyRun> lastRun = new HashMap<>();
        for (ChantierAutonomyRun run : store.recentOf(userId, workspaceId)) {
            lastRun.putIfAbsent(run.getChantierNumber(), run);
        }
        SubjectJourney journey = journeys.forTurn(userId, workspaceId);
        BigDecimal average = averageTurnCost(userId);
        List<BoardEntry> entries = new ArrayList<>();
        for (ChantierReadiness c : readiness) {
            ChantierAutonomyRun run = lastRun.get(c.number());
            ChantierSnapshot snapshot = ChantierSnapshot.of(journey, c.number());
            entries.add(new BoardEntry(c.number(), c.title(), c.preflight(), run == null ? null : RunView.of(run),
                    estimate(average, snapshot.remaining().size())));
        }
        return new Board(List.copyOf(entries), store.countRunning(userId), maxParallel);
    }

    /** Les courses du compte : en cours d'abord, puis les 20 dernières (F-220). */
    public List<RunView> runs(UUID userId) {
        return store.ofUser(userId).stream().map(RunView::of).toList();
    }

    /** Le coût moyen d'un tour du compte sur 30 jours, ou {@code null} sans mesure. */
    BigDecimal averageTurnCost(UUID userId) {
        if (usageTurns == null) {
            return null;
        }
        try {
            Double avg = usageTurns.averageCostSince(userId, OffsetDateTime.now(clock).minusDays(ESTIMATE_DAYS));
            return avg == null || avg <= 0 ? null : BigDecimal.valueOf(avg);
        } catch (RuntimeException e) {
            return null; // best-effort : sans mesure, pas d'estimation
        }
    }

    /** Tours estimés : une étape restante ≈ un demi-tour, au moins un, au plus le plafond. */
    Estimate estimate(BigDecimal averageTurnCost, int remainingSteps) {
        if (averageTurnCost == null || remainingSteps <= 0) {
            return null;
        }
        int turns = Math.min(runner.maxTurns(), Math.max(1, (remainingSteps + 1) / 2));
        return new Estimate(averageTurnCost.multiply(BigDecimal.valueOf(turns)).setScale(2, RoundingMode.HALF_UP),
                turns, "coût moyen de vos tours sur " + ESTIMATE_DAYS + " jours × " + turns + " tour(s)");
    }

    // ------------------------------------------------------------------ départ

    /**
     * [Lancer en autonome] le chantier ouvert {@code number}.
     *
     * @throws AutonomyConflictException pas prêt, sujet occupé, limite atteinte, poste hors ligne (409)
     * @throws InvalidJourneyException   le chantier n'est pas ouvert (400)
     */
    public synchronized RunView launch(UUID userId, UUID workspaceId, int number) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        ChantierReadiness readiness = preflights.forOwned(userId, workspace).stream()
                .filter(c -> c.number() == number)
                .findFirst()
                .orElseThrow(() -> new InvalidJourneyException("Le chantier " + number + " n'est pas ouvert."));
        if (!readiness.preflight().ready()) {
            throw new AutonomyConflictException("autonomy_not_ready", "Ce chantier n'est pas prêt à partir en "
                    + "autonome.", readiness.preflight().reasons());
        }
        if (store.running(userId, workspaceId).isPresent() || liveTurns.find(userId, workspaceId).isPresent()) {
            throw busy();
        }
        if (store.countRunning(userId) >= maxParallel) {
            String count = maxParallel == 4 ? "Quatre" : String.valueOf(maxParallel);
            throw new AutonomyConflictException("autonomy_limit_reached", count + " chantiers autonomes au maximum "
                    + "en même temps : attendez qu'un finisse ou reprenez la main sur l'un d'eux.");
        }
        if (hostOffline(userId, workspace)) {
            throw new AutonomyConflictException("autonomy_host_offline", "Le poste de ce sujet est hors ligne : le "
                    + "chantier ne peut pas partir maintenant.");
        }
        journeys.focus(userId, workspaceId, number); // le chantier autonome est celui que le sujet montre
        ChantierSnapshot start = ChantierSnapshot.of(journeys.forTurn(userId, workspaceId), number);
        Optional<LiveTurn> turn = runner.prepare(userId, workspaceId,
                AutonomyTurnRunner.firstDemand(number, start.title(), start.plan(), runner.maxTurns()));
        if (turn.isEmpty()) {
            throw busy();
        }
        ChantierAutonomyRun run = runner.launch(turn.get(), workspace, number, start);
        log.info("Chantier lancé en autonome (workspace={}, chantier={}, course={})", workspaceId, number, run.getId());
        return RunView.of(store.find(run.getId()).orElse(run));
    }

    private static AutonomyConflictException busy() {
        return new AutonomyConflictException("autonomy_subject_busy", "Un tour tourne déjà dans ce sujet : un "
                + "chantier autonome par sujet à la fois.");
    }

    /** Le poste du sujet est hors ligne — seulement pour un terminal qui s'exécute sur un poste. */
    private boolean hostOffline(UUID userId, Workspace workspace) {
        if (liveness == null || !workspace.isRunnerTarget() || workspace.getHostId() == null) {
            return false;
        }
        return !liveness.isAlive(userId, workspace.getHostId());
    }

    /**
     * [Reprendre la main] : la course passe {@code STOPPED} tout de suite, le tour est interrompu (relayé entre
     * pods) ; la boucle s'arrête à la frontière sûre suivante sans réécrire l'issue.
     *
     * @throws AutonomyConflictException aucune course en cours sur ce sujet (409)
     */
    public RunView stop(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        ChantierAutonomyRun run = store.running(userId, workspaceId)
                .orElseThrow(() -> new AutonomyConflictException("autonomy_not_running",
                        "Aucun chantier ne tourne en autonome dans ce sujet."));
        store.finish(run.getId(), AutonomyRunStatus.STOPPED, AutonomyTurnRunner.TAKEN_BACK, null);
        try {
            chatService.interruptChat(userId, workspaceId);
        } catch (RuntimeException e) {
            log.warn("Interruption d'un chantier autonome en échec ({})", e.getClass().getSimpleName());
        }
        return RunView.of(store.find(run.getId()).orElse(run));
    }

    // ------------------------------------------------------------------ départ dit à l'agent (SF-219-04)

    /**
     * L'outil {@code lancer_chantier_autonome} : mêmes gardes que le bouton, puis le départ est <b>programmé</b>
     * — il part dès que le tour courant est fini ({@link #launchDeferred}). Un nouvel appel sur le même sujet
     * remplace le précédent.
     */
    @Override
    public Result schedule(UUID userId, Workspace workspace, int number) {
        Optional<ChantierReadiness> readiness = preflights.forOwned(userId, workspace).stream()
                .filter(c -> c.number() == number)
                .findFirst();
        if (readiness.isEmpty()) {
            return new Result(false, "Le chantier " + number + " n'est pas ouvert : rien n'est programmé.");
        }
        ChantierPreflight preflight = readiness.get().preflight();
        if (!preflight.ready()) {
            return new Result(false, "Le chantier " + number + " n'est pas prêt à partir en autonome : "
                    + String.join(" ; ", preflight.reasons().stream().map(ChantierPreflight.Reason::label).toList())
                    + " Dis-le à l'utilisateur ; rien n'est programmé.");
        }
        if (store.running(userId, workspace.getId()).isPresent()) {
            return new Result(false, "Un chantier tourne déjà en autonome dans ce sujet : un à la fois.");
        }
        if (store.countRunning(userId) >= maxParallel) {
            return new Result(false, maxParallel + " chantiers autonomes tournent déjà sur le compte : c'est le "
                    + "maximum. Dis-le à l'utilisateur.");
        }
        if (hostOffline(userId, workspace)) {
            return new Result(false, "Le poste de ce sujet est hors ligne : le chantier ne peut pas partir.");
        }
        deferred.put(userId + "/" + workspace.getId(), new Deferred(userId, workspace.getId(), number,
                OffsetDateTime.now(clock).plus(deferredTtl)));
        return new Result(true, "Départ programmé : le chantier " + number + " partira en autonome dès la fin de "
                + "ce tour (si le service redémarre d'ici là, il faudra le relancer). Termine ton tour maintenant, "
                + "en une phrase ; n'exécute pas les étapes toi-même.");
    }

    /**
     * Lance les départs programmés dont le sujet est libre ; écarte ceux qui ont expiré. Un départ refusé au
     * moment de partir (préflight devenu rouge, limite atteinte…) est abandonné et notifié.
     *
     * @return le nombre de départs lancés
     */
    public int launchDeferred() {
        int launched = 0;
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (Map.Entry<String, Deferred> entry : List.copyOf(deferred.entrySet())) {
            Deferred d = entry.getValue();
            if (d.expiresAt().isBefore(now)) {
                deferred.remove(entry.getKey(), d);
                continue;
            }
            if (liveTurns.find(d.userId(), d.workspaceId()).isPresent()) {
                continue; // le tour qui a programmé le départ n'est pas fini
            }
            if (!deferred.remove(entry.getKey(), d)) {
                continue;
            }
            try {
                launch(d.userId(), d.workspaceId(), d.number());
                launched++;
            } catch (AutonomyConflictException | InvalidJourneyException e) {
                log.info("Départ autonome programmé abandonné (workspace={}, chantier={}) : {}", d.workspaceId(),
                        d.number(), e.getMessage());
                if (notifier != null) {
                    notifier.notify(d.userId(), d.workspaceId(), AutonomyRunStatus.BLOCKED, false);
                }
            } catch (RuntimeException e) {
                log.warn("Départ autonome programmé en échec ({})", e.getClass().getSimpleName());
            }
        }
        return launched;
    }

    /** Vrai si un départ est programmé sur ce sujet (tests, écran). */
    public boolean hasDeferred(UUID userId, UUID workspaceId) {
        return deferred.containsKey(userId + "/" + workspaceId);
    }

    // ------------------------------------------------------------------ orphelines

    /** Ferme les courses sans signe de vie (pod arrêté) ; rend leur nombre. */
    public int closeStale() {
        int closed = 0;
        for (ChantierAutonomyRun run : store.staleRunning(OffsetDateTime.now(clock).minus(staleAfter))) {
            if (store.finish(run.getId(), AutonomyRunStatus.FAILED, "La course s'est interrompue (redémarrage du "
                    + "service) : relancez-la.", "orphaned")) {
                closed++;
                if (notifier != null) {
                    notifier.notify(run.getUserId(), run.getWorkspaceId(), AutonomyRunStatus.FAILED, false);
                }
            }
        }
        return closed;
    }
}
