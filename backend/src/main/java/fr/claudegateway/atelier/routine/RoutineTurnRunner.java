package fr.claudegateway.atelier.routine;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.atelier.AtelierAccessDeniedException;
import fr.claudegateway.atelier.AtelierChatService;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.AtelierMcpTurnLauncher;
import fr.claudegateway.atelier.AtelierProgressListener;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.byok.ByokKeyRequiredException;
import fr.claudegateway.quota.QuotaExceededException;

/**
 * <b>Une routine tourne seule</b> (F-183 / SF-183-02, D2) : un tour démarre <b>sans écran</b> dans le
 * terminal du sujet, par le mécanisme du tour vivant (F-84) — exactement comme un tour lancé par MCP
 * (F-112 / SF-112-05), dont il réutilise l'écouteur.
 *
 * <p><b>Rien n'est dégradé</b> (justesse avant coût) : la boucle est {@link AtelierChatService#chatStreaming}
 * — même modèle, même effort de raisonnement, mêmes outils et même consigne qu'au clavier. La demande est
 * la consigne de la routine, précédée de son nom ; elle est persistée dans le fil comme toute demande.</p>
 *
 * <p>En deux temps : {@link #prepare} ouvre le tour vivant <b>seulement si le sujet est libre</b> (jamais
 * on ne coupe ni on ne se glisse dans le travail de l'utilisateur) ; {@link #launch} écrit l'exécution
 * {@code RUNNING} et lance la boucle en tâche de fond ; {@link #abandon} referme un tour préparé quand le
 * créneau a été pris par un autre pod.</p>
 */
@Service
public class RoutineTurnRunner {

    private static final Logger log = LoggerFactory.getLogger(RoutineTurnRunner.class);

    private final AtelierChatService chatService;
    private final LiveTurnRegistry liveTurns;
    private final AtelierMcpTurnLauncher headless;
    private final Executor executor;
    private final RoutineRunService runs;

    public RoutineTurnRunner(AtelierChatService chatService, LiveTurnRegistry liveTurns,
            AtelierMcpTurnLauncher headless, @Qualifier("chatStreamExecutor") Executor executor,
            RoutineRunService runs) {
        this.chatService = chatService;
        this.liveTurns = liveTurns;
        this.headless = headless;
        this.executor = executor;
        this.runs = runs;
    }

    /** La demande envoyée au modèle : le nom de la routine, puis sa consigne telle quelle. */
    public static String demandOf(SubjectRoutine routine) {
        return "Routine « " + routine.getName() + " » (exécution automatique, sans écran ouvert) :\n\n"
                + routine.getInstruction();
    }

    /** Ouvre le tour vivant si — et seulement si — le sujet est libre sur ce pod. */
    public Optional<LiveTurn> prepare(SubjectRoutine routine) {
        return liveTurns.openIfIdle(routine.getUserId(), routine.getWorkspaceId(), demandOf(routine));
    }

    /** Referme un tour préparé qui ne partira pas. */
    public void abandon(LiveTurn turn) {
        liveTurns.close(turn);
    }

    /**
     * Écrit l'exécution {@code RUNNING} et lance la boucle en tâche de fond.
     *
     * @param capUsd plafond en dollars de l'exécution (SF-183-03), ou {@code null}
     * @return l'exécution ouverte
     */
    public RoutineRun launch(LiveTurn turn, SubjectRoutine routine, RoutineTrigger trigger, OffsetDateTime slot,
            BigDecimal capUsd) {
        RoutineRun run;
        try {
            run = runs.open(routine, trigger, slot, turn.turnId(), capUsd);
        } catch (RuntimeException e) {
            liveTurns.close(turn);
            throw e;
        }
        try {
            executor.execute(() -> runLoop(turn, routine, run));
        } catch (RejectedExecutionException e) {
            liveTurns.close(turn);
            runs.finish(run.getId(), RoutineRunStatus.FAILED, BigDecimal.ZERO, "executor_busy");
        }
        return run;
    }

    /** Le résultat d'une exécution, tel que le journal le retient. */
    record Outcome(RoutineRunStatus status, BigDecimal costUsd, String errorCode) {
    }

    /** La boucle, en tâche de fond : reflet du lanceur MCP, avec le journal des exécutions. */
    void runLoop(LiveTurn turn, SubjectRoutine routine, RoutineRun run) {
        Outcome outcome = new Outcome(RoutineRunStatus.FAILED, BigDecimal.ZERO, "internal_error");
        try {
            outcome = execute(turn, routine);
        } finally {
            liveTurns.close(turn);
            runs.finish(run.getId(), outcome.status(), outcome.costUsd(), outcome.errorCode());
            log.info("Routine exécutée (routine={}, workspace={}, tour={}, issue={})", routine.getId(),
                    routine.getWorkspaceId(), turn.turnId(), outcome.status());
        }
    }

    /** Le cœur : les tours de la routine (le premier, puis les tours de suite des précisions). */
    Outcome execute(LiveTurn turn, SubjectRoutine routine) {
        UUID userId = routine.getUserId();
        UUID workspaceId = routine.getWorkspaceId();
        BigDecimal spent = BigDecimal.ZERO;
        AtomicBoolean machineLost = new AtomicBoolean(false);
        boolean incomplete = false;
        try {
            turn.publish("started", Map.of("turnId", turn.turnId().toString(), "startedAt", turn.startedAtMs()));
            turn.publish("text", Map.of("text", "_Routine « " + routine.getName() + " » — exécution automatique._\n"));
            AtelierProgressListener listener = headless.headlessListener(turn, () -> machineLost.set(true));
            String demand = demandOf(routine);
            for (;;) {
                AtelierChatResult result = chatService.chatStreaming(userId, workspaceId, demand, listener);
                spent = spent.add(result.costUsd() == null ? BigDecimal.ZERO : result.costUsd());
                incomplete = incomplete || result.budgetReached();
                if (result.stoppedByMachine()) {
                    machineLost.set(true);
                }
                if (result.interrupted()) {
                    turn.publishSteersDropped(turn.sealAndDrain(), "interrupted");
                    turn.publish("done", done(result, false));
                    return new Outcome(RoutineRunStatus.INTERRUPTED, spent, null);
                }
                Optional<LiveTurn.Steer> followUp = turn.pollFollowUpOrSeal();
                turn.publish("done", done(result, followUp.isPresent()));
                if (followUp.isEmpty()) {
                    break;
                }
                turn.publish(LiveTurn.STEER_FOLLOWUP, Map.of("steerId", followUp.get().steerId()));
                demand = followUp.get().text();
            }
            if (machineLost.get()) {
                return new Outcome(RoutineRunStatus.FAILED, spent, "runner_offline");
            }
            return new Outcome(incomplete ? RoutineRunStatus.INCOMPLETE : RoutineRunStatus.DONE, spent, null);
        } catch (AtelierAccessDeniedException ex) {
            return fail(turn, spent, "forbidden");
        } catch (QuotaExceededException ex) {
            return fail(turn, spent, "quota_exceeded");
        } catch (ByokKeyRequiredException ex) {
            return fail(turn, spent, "byok_key_required");
        } catch (WorkspaceNotFoundException ex) {
            return fail(turn, spent, "workspace_not_found");
        } catch (AIProviderUnavailableException ex) {
            return fail(turn, spent, "provider_unavailable");
        } catch (AIProviderException ex) {
            return fail(turn, spent, "provider_error");
        } catch (RuntimeException ex) {
            log.warn("Échec inattendu d'une routine ({})", ex.getClass().getSimpleName());
            return fail(turn, spent, "internal_error");
        }
    }

    private static Outcome fail(LiveTurn turn, BigDecimal spent, String code) {
        turn.publishSteersDropped(turn.sealAndDrain(), code);
        turn.publish("error", Map.of("error", code));
        return new Outcome(RoutineRunStatus.FAILED, spent, code);
    }

    private static Map<String, Object> done(AtelierChatResult result, boolean followUp) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
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
