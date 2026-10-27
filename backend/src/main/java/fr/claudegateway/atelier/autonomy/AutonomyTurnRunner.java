package fr.claudegateway.atelier.autonomy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.atelier.AtelierAccessDeniedException;
import fr.claudegateway.atelier.AtelierChatService;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.AtelierMcpTurnLauncher;
import fr.claudegateway.atelier.AtelierProgressListener;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.journey.ChantierPreflight;
import fr.claudegateway.atelier.journey.ChantierPreflightService;
import fr.claudegateway.atelier.journey.JourneyPlan;
import fr.claudegateway.atelier.journey.SubjectJourneyService;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.byok.ByokKeyRequiredException;
import fr.claudegateway.quota.QuotaExceededException;

/**
 * <b>Un chantier part en autonome</b> (F-219 / SF-219-02) : des tours <b>sans écran</b> dans le terminal du
 * sujet, par le mécanisme du tour vivant (F-84) — exactement comme une routine (F-183), dont il reprend le
 * schéma : ouvrir le tour si le sujet est libre, boucle en tâche de fond, journal, une notification de fin.
 *
 * <p><b>Rien n'est dégradé</b> (justesse avant coût) : chaque tour est {@link AtelierChatService#chatStreaming}
 * — même modèle, même raisonnement, mêmes outils qu'au clavier. La consigne d'autonomie est dans le
 * <b>message</b> du tour, jamais dans la consigne système (préfixe stable).</p>
 *
 * <p><b>Enchaînement borné</b> : un tour a un plafond d'appels d'outils ; un chantier de 40 étapes ne tient pas
 * dans un tour. Après chaque tour, la gateway relit le plan : tout vérifié → {@code DONE} ; préflight rouge
 * (étape bloquée, attente, amendement…) → {@code BLOCKED} ; aucune avancée → {@code BLOCKED} ; sinon tour
 * suivant, au plus {@code app.autonomy.max-turns}.</p>
 */
@Service
public class AutonomyTurnRunner {

    private static final Logger log = LoggerFactory.getLogger(AutonomyTurnRunner.class);

    /** Titre d'étape montré dans la consigne, au plus. */
    static final int MAX_STEP_TITLE = 160;

    /** La raison d'une reprise en main. */
    static final String TAKEN_BACK = "Reprise en main.";

    private final AtelierChatService chatService;
    private final LiveTurnRegistry liveTurns;
    private final AtelierMcpTurnLauncher headless;
    private final Executor executor;
    private final AutonomyRunStore store;
    private final SubjectJourneyService journeys;
    private final ChantierPreflightService preflights;
    private final int maxTurns;

    private AutonomyNotifier notifier;

    public AutonomyTurnRunner(AtelierChatService chatService, LiveTurnRegistry liveTurns,
            AtelierMcpTurnLauncher headless, @Qualifier("chatStreamExecutor") Executor executor,
            AutonomyRunStore store, SubjectJourneyService journeys, ChantierPreflightService preflights,
            @Value("${app.autonomy.max-turns:8}") int maxTurns) {
        this.chatService = chatService;
        this.liveTurns = liveTurns;
        this.headless = headless;
        this.executor = executor;
        this.store = store;
        this.journeys = journeys;
        this.preflights = preflights;
        this.maxTurns = Math.max(1, maxTurns);
    }

    @Autowired(required = false)
    public void setNotifier(AutonomyNotifier notifier) {
        this.notifier = notifier;
    }

    public int maxTurns() {
        return maxTurns;
    }

    // ------------------------------------------------------------------ la consigne (dans le message)

    /** La demande du premier tour. */
    public static String firstDemand(int number, String title, JourneyPlan plan, int maxTurns) {
        return header(number, title, 1, maxTurns) + rules(number) + remainingSteps(plan);
    }

    /** La demande d'un tour de suite, précédée des précisions que l'utilisateur a envoyées entre-temps. */
    public static String continuation(int number, String title, JourneyPlan plan, int turn, int maxTurns,
            List<String> userNotes) {
        StringBuilder demand = new StringBuilder();
        if (userNotes != null && !userNotes.isEmpty()) {
            demand.append("Précisions de l'utilisateur pendant la course :\n");
            userNotes.forEach(note -> demand.append("- ").append(note.strip()).append('\n'));
            demand.append('\n');
        }
        return demand.append("Poursuis. ").append(header(number, title, turn, maxTurns)).append(rules(number))
                .append(remainingSteps(plan)).toString();
    }

    private static String header(int number, String title, int turn, int maxTurns) {
        return "Chantier " + number + (title == null || title.isBlank() ? "" : " « " + title.strip() + " »")
                + " — EXÉCUTION AUTONOME lancée par l'utilisateur, sans écran ouvert (tour " + turn + "/"
                + maxTurns + ").\n";
    }

    private static String rules(int number) {
        return "Le plan validé vaut feu vert : exécute TOUTES les étapes restantes, dans l'ordre de leurs "
                + "dépendances, puis vérifie chacune comme le plan le dit (update_plan_step FAIT, puis VERIFIE ou "
                + "ECHEC avec la preuve). Passe chantier: " + number + " à chaque outil du parcours. Ne pose "
                + "AUCUNE question et n'attends aucune confirmation : personne ne regarde. Sur un VRAI blocage "
                + "(information manquante découverte en route, échec sans remède), update_plan_step status=BLOQUE "
                + "avec la raison, puis arrête-toi. Ne coche rien sans preuve.\n";
    }

    private static String remainingSteps(JourneyPlan plan) {
        StringBuilder list = new StringBuilder("Étapes restantes :\n");
        for (int i = 0; i < plan.steps().size(); i++) {
            JourneyPlan.Step step = plan.steps().get(i);
            if (step.status() == JourneyPlan.StepStatus.VERIFIE) {
                continue;
            }
            String title = step.title().length() > MAX_STEP_TITLE
                    ? step.title().substring(0, MAX_STEP_TITLE - 1) + "…" : step.title();
            list.append(i + 1).append(". [").append(label(step.status())).append("] ").append(title).append('\n');
        }
        return list.toString();
    }

    private static String label(JourneyPlan.StepStatus status) {
        return switch (status) {
            case A_FAIRE -> "à faire";
            case FAIT -> "faite, à vérifier";
            case VERIFIE -> "vérifiée";
            case ECHEC -> "échec";
            case BLOQUE -> "bloquée";
        };
    }

    // ------------------------------------------------------------------ départ

    /** Ouvre le tour vivant si — et seulement si — le sujet est libre sur ce pod. */
    public Optional<LiveTurn> prepare(UUID userId, UUID workspaceId, String demand) {
        return liveTurns.openIfIdle(userId, workspaceId, demand);
    }

    /**
     * Écrit la course {@code RUNNING} et lance la boucle en tâche de fond.
     *
     * @param workspace le terminal, <b>déjà</b> vérifié comme possédé par l'appelant
     */
    public ChantierAutonomyRun launch(LiveTurn turn, Workspace workspace, int number, ChantierSnapshot start) {
        UUID userId = turn.userId();
        ChantierAutonomyRun run;
        try {
            run = store.open(userId, workspace.getId(), number, start.title(), turn.turnId(), start.done(),
                    start.total());
        } catch (RuntimeException e) {
            liveTurns.close(turn);
            throw e;
        }
        try {
            executor.execute(() -> runLoop(turn, workspace, run));
        } catch (RejectedExecutionException e) {
            liveTurns.close(turn);
            if (store.finish(run.getId(), AutonomyRunStatus.FAILED, "Le service est saturé : relancez dans un "
                    + "instant.", "executor_busy")) {
                notifyOutcome(userId, workspace.getId(), AutonomyRunStatus.FAILED, false);
            }
        }
        return run;
    }

    /** L'issue d'une course. */
    record Outcome(AutonomyRunStatus status, String reason, String code, boolean byUser) {
    }

    void runLoop(LiveTurn turn, Workspace workspace, ChantierAutonomyRun run) {
        Outcome outcome = new Outcome(AutonomyRunStatus.FAILED, "Erreur interne.", "internal_error", false);
        try {
            outcome = execute(turn, workspace, run);
        } finally {
            liveTurns.close(turn);
            boolean closedHere = store.finish(run.getId(), outcome.status(), outcome.reason(), outcome.code());
            if (closedHere) {
                notifyOutcome(run.getUserId(), workspace.getId(), outcome.status(), outcome.byUser());
            }
            log.info("Chantier autonome terminé (workspace={}, chantier={}, course={}, issue={})", workspace.getId(),
                    run.getChantierNumber(), run.getId(), closedHere ? outcome.status() : "déjà close");
        }
    }

    /** La boucle des tours. */
    Outcome execute(LiveTurn turn, Workspace workspace, ChantierAutonomyRun run) {
        UUID userId = run.getUserId();
        UUID workspaceId = workspace.getId();
        int number = run.getChantierNumber();
        BigDecimal spent = BigDecimal.ZERO;
        AtomicBoolean machineLost = new AtomicBoolean(false);
        chatService.muteOutcomePush(userId, workspaceId);
        try {
            turn.publish("started", Map.of("turnId", turn.turnId().toString(), "startedAt", turn.startedAtMs()));
            turn.publish("text", Map.of("text", "_Chantier " + number + " — exécution autonome._\n"));
            AtelierProgressListener listener = headless.headlessListener(turn, () -> machineLost.set(true));
            ChantierSnapshot before = snapshot(userId, workspaceId, number);
            String demand = firstDemand(number, before.title(), before.plan(), maxTurns);
            for (int k = 1;; k++) {
                AtelierChatResult result = chatService.chatStreaming(userId, workspaceId, demand, listener);
                spent = spent.add(result.costUsd() == null ? BigDecimal.ZERO : result.costUsd());
                if (result.stoppedByMachine()) {
                    machineLost.set(true);
                }
                ChantierSnapshot after = snapshot(userId, workspaceId, number);
                store.heartbeat(run.getId(), k, spent, after.done(), after.total());
                if (result.interrupted() || !store.isRunning(run.getId())) {
                    turn.publishSteersDropped(turn.sealAndDrain(), "interrupted");
                    turn.publish("done", done(result, false));
                    return new Outcome(AutonomyRunStatus.STOPPED, TAKEN_BACK, null, true);
                }
                if (machineLost.get()) {
                    turn.publishSteersDropped(turn.sealAndDrain(), "runner_offline");
                    turn.publish("done", done(result, false));
                    return new Outcome(AutonomyRunStatus.FAILED, "Le poste ne répond plus.", "runner_offline", false);
                }
                Outcome end = judge(userId, workspace, number, before, after, k);
                if (end != null) {
                    turn.publishSteersDropped(turn.sealAndDrain(), "autonomy_ended");
                    turn.publish("done", done(result, false));
                    return end;
                }
                List<String> notes = turn.takeSteers().stream().map(LiveTurn.Steer::text).toList();
                turn.publish("done", done(result, true));
                turn.publish("text", Map.of("text", "_Chantier " + number + " — tour " + (k + 1) + "/" + maxTurns
                        + " en autonome._\n"));
                demand = continuation(number, after.title(), after.plan(), k + 1, maxTurns, notes);
                before = after;
            }
        } catch (AtelierAccessDeniedException ex) {
            return fail(turn, "forbidden");
        } catch (QuotaExceededException ex) {
            return fail(turn, "quota_exceeded");
        } catch (ByokKeyRequiredException ex) {
            return fail(turn, "byok_key_required");
        } catch (WorkspaceNotFoundException ex) {
            return fail(turn, "workspace_not_found");
        } catch (AIProviderUnavailableException ex) {
            return fail(turn, "provider_unavailable");
        } catch (AIProviderException ex) {
            return fail(turn, "provider_error");
        } catch (RuntimeException ex) {
            log.warn("Échec inattendu d'un chantier autonome ({})", ex.getClass().getSimpleName());
            return fail(turn, "internal_error");
        } finally {
            chatService.unmuteOutcomePush(userId, workspaceId);
        }
    }

    /**
     * Le jugement après un tour : l'issue de la course, ou {@code null} pour un tour de plus.
     */
    Outcome judge(UUID userId, Workspace workspace, int number, ChantierSnapshot before, ChantierSnapshot after,
            int turnsDone) {
        if (!after.open()) {
            return new Outcome(AutonomyRunStatus.STOPPED, "Le chantier n'est plus ouvert.", null, true);
        }
        if (after.allVerified() || after.closeProposed()) {
            return new Outcome(AutonomyRunStatus.DONE, "Toutes les étapes sont vérifiées : la clôture est proposée.",
                    null, false);
        }
        Optional<ChantierPreflight> preflight = preflights.forOwned(userId, workspace).stream()
                .filter(c -> c.number() == number)
                .map(ChantierPreflightService.ChantierReadiness::preflight)
                .findFirst();
        if (preflight.isPresent() && !preflight.get().ready()) {
            return new Outcome(AutonomyRunStatus.BLOCKED, preflight.get().reasons().get(0).label(), null, false);
        }
        if (after.progress() <= before.progress()) {
            return new Outcome(AutonomyRunStatus.BLOCKED, "Aucune avancée sur un tour complet : aucune étape n'a "
                    + "progressé.", null, false);
        }
        if (turnsDone >= maxTurns) {
            return new Outcome(AutonomyRunStatus.STOPPED, "Plafond de " + maxTurns + " tours atteint : relancez "
                    + "pour continuer.", null, false);
        }
        return null;
    }

    private ChantierSnapshot snapshot(UUID userId, UUID workspaceId, int number) {
        return ChantierSnapshot.of(journeys.forTurn(userId, workspaceId), number);
    }

    private void notifyOutcome(UUID userId, UUID workspaceId, AutonomyRunStatus status, boolean byUser) {
        if (notifier != null) {
            notifier.notify(userId, workspaceId, status, byUser);
        }
    }

    private static Outcome fail(LiveTurn turn, String code) {
        turn.publishSteersDropped(turn.sealAndDrain(), code);
        turn.publish("error", Map.of("error", code));
        return new Outcome(AutonomyRunStatus.FAILED, "Erreur pendant la course (" + code + ").", code, false);
    }

    private static Map<String, Object> done(AtelierChatResult result, boolean followUp) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reply", result.reply());
        map.put("actions", result.actions());
        map.put("messageId", result.messageId() == null ? null : result.messageId().toString());
        map.put("inputTokens", result.inputTokens());
        map.put("outputTokens", result.outputTokens());
        map.put("activeSeconds", result.activeSeconds());
        map.put("budgetReached", result.budgetReached());
        map.put("followUp", followUp);
        return map;
    }
}
