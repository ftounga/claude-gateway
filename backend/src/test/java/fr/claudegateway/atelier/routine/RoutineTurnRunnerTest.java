package fr.claudegateway.atelier.routine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierChatService;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.AtelierMcpTurnLauncher;
import fr.claudegateway.atelier.AtelierProgressListener;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.quota.QuotaExceededException;

/**
 * F-183 / SF-183-02 — l'exécution d'une routine : un tour sans écran, le statut et le coût au journal,
 * jamais de tour sur un sujet occupé.
 */
class RoutineTurnRunnerTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private AtelierChatService chat;
    private LiveTurnRegistry liveTurns;
    private AtelierMcpTurnLauncher headless;
    private RoutineRunService runs;
    private RoutineTurnRunner runner;
    private SubjectRoutine routine;
    private final Executor direct = Runnable::run;

    @BeforeEach
    void setUp() {
        chat = mock(AtelierChatService.class);
        liveTurns = new LiveTurnRegistry(new ObjectMapper());
        headless = mock(AtelierMcpTurnLauncher.class);
        when(headless.headlessListener(any(), any())).thenReturn(AtelierProgressListener.NOOP);
        runs = mock(RoutineRunService.class);
        runner = new RoutineTurnRunner(chat, liveTurns, headless, direct, runs);
        routine = SubjectRoutine.builder().id(UUID.randomUUID()).userId(userId).workspaceId(workspaceId)
                .name("Point du matin").instruction("le CR du daily d'aujourd'hui, version scriptée")
                .cadence(RoutineCadence.WEEKDAYS).runTime("08:00").timeZone("Europe/Paris")
                .maxRunUsd(new BigDecimal("5.00")).origin(RoutineOrigin.USER).build();
        when(runs.open(any(), any(), any(), any(), any())).thenAnswer(inv -> RoutineRun.builder()
                .id(UUID.randomUUID()).status(RoutineRunStatus.RUNNING).capUsd(inv.getArgument(4)).build());
    }

    private static AtelierChatResult result(String reply, boolean budgetReached, String cost) {
        return new AtelierChatResult(reply, List.of(), UUID.randomUUID(), 10, 5, 3, budgetReached,
                new BigDecimal(cost), null);
    }

    @Test
    void theDemandCarriesTheNameAndTheInstruction() {
        assertThat(RoutineTurnRunner.demandOf(routine))
                .startsWith("Routine « Point du matin »")
                .endsWith("le CR du daily d'aujourd'hui, version scriptée");
    }

    @Test
    void aNormalRunIsDoneWithItsCost() {
        when(chat.chatStreaming(eq(userId), eq(workspaceId), anyString(), any())).thenReturn(result("CR prêt", false, "0.84"));
        LiveTurn turn = runner.prepare(routine).orElseThrow();
        runner.launch(turn, routine, RoutineTrigger.SCHEDULED, null, new BigDecimal("5.00"));
        verify(runs).finish(any(), eq(RoutineRunStatus.DONE), eq(new BigDecimal("0.84")), eq(null));
        verify(chat).chatStreaming(eq(userId), eq(workspaceId), eq(RoutineTurnRunner.demandOf(routine)), any());
        assertThat(liveTurns.find(userId, workspaceId)).isEmpty();
    }

    @Test
    void eachTurnIsArmedWithWhatIsLeftThenReleased() {
        when(chat.chatStreaming(any(), any(), anyString(), any())).thenReturn(result("ok", false, "0.84"));
        runner.launch(runner.prepare(routine).orElseThrow(), routine, RoutineTrigger.SCHEDULED, null,
                new BigDecimal("5.00"));
        verify(chat).capSpend(userId, workspaceId, new BigDecimal("5.00"));
        verify(chat).releaseSpendCap(userId, workspaceId);
    }

    @Test
    void noFollowUpOnceTheBudgetIsSpent() {
        when(runs.open(any(), any(), any(), any(), any())).thenAnswer(inv -> RoutineRun.builder()
                .id(UUID.randomUUID()).status(RoutineRunStatus.RUNNING).capUsd(new BigDecimal("1.00")).build());
        when(chat.chatStreaming(any(), any(), anyString(), any())).thenAnswer(inv -> {
            // Une précision arrive pendant le tour : elle ne rouvrira pas de tour, le budget est épuisé.
            liveTurns.find(userId, workspaceId).orElseThrow().offerSteer("et aussi ceci");
            return result("coupé", true, "1.02");
        });
        runner.launch(runner.prepare(routine).orElseThrow(), routine, RoutineTrigger.SCHEDULED, null,
                new BigDecimal("1.00"));
        verify(chat, org.mockito.Mockito.times(1)).chatStreaming(any(), any(), anyString(), any());
        verify(runs).finish(any(), eq(RoutineRunStatus.INCOMPLETE), eq(new BigDecimal("1.02")), eq(null));
    }

    @Test
    void aCappedRunIsIncomplete() {
        when(chat.chatStreaming(any(), any(), anyString(), any())).thenReturn(result("coupé", true, "5.10"));
        runner.launch(runner.prepare(routine).orElseThrow(), routine, RoutineTrigger.SCHEDULED, null, null);
        verify(runs).finish(any(), eq(RoutineRunStatus.INCOMPLETE), eq(new BigDecimal("5.10")), eq(null));
    }

    @Test
    void quotaExhaustedFailsWithACode() {
        when(chat.chatStreaming(any(), any(), anyString(), any())).thenThrow(new QuotaExceededException("épuisé"));
        runner.launch(runner.prepare(routine).orElseThrow(), routine, RoutineTrigger.SCHEDULED, null, null);
        verify(runs).finish(any(), eq(RoutineRunStatus.FAILED), eq(BigDecimal.ZERO), eq("quota_exceeded"));
    }

    @Test
    void aBusySubjectIsNeverTaken() {
        liveTurns.open(userId, workspaceId);
        Optional<LiveTurn> prepared = runner.prepare(routine);
        assertThat(prepared).isEmpty();
    }
}
