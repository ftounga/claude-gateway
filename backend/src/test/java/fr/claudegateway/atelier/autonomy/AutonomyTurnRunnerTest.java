package fr.claudegateway.atelier.autonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.atelier.AtelierChatService;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.AtelierMcpTurnLauncher;
import fr.claudegateway.atelier.AtelierProgressListener;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.journey.ChantierPreflight;
import fr.claudegateway.atelier.journey.ChantierPreflightService;
import fr.claudegateway.atelier.journey.JourneyMode;
import fr.claudegateway.atelier.journey.JourneyPhase;
import fr.claudegateway.atelier.journey.JourneyPlan;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyService;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;

/**
 * F-219 / SF-219-02 — la boucle d'un chantier autonome : jusqu'au bout, arrêt propre sur blocage, aucune
 * avancée, plafond, reprise en main, erreur — et la consigne dans le message.
 */
class AutonomyTurnRunnerTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private AtelierChatService chat;
    private LiveTurnRegistry liveTurns;
    private AutonomyRunStore store;
    private SubjectJourneyService journeys;
    private ChantierPreflightService preflights;
    private AutonomyNotifier notifier;
    private AutonomyTurnRunner runner;
    private Workspace workspace;
    private final Deque<String> plans = new ArrayDeque<>();
    private final Executor direct = Runnable::run;
    private ChantierAutonomyRun run;

    @BeforeEach
    void setUp() {
        chat = mock(AtelierChatService.class);
        liveTurns = new LiveTurnRegistry(new ObjectMapper());
        AtelierMcpTurnLauncher headless = mock(AtelierMcpTurnLauncher.class);
        when(headless.headlessListener(any(), any())).thenReturn(AtelierProgressListener.NOOP);
        store = mock(AutonomyRunStore.class);
        journeys = mock(SubjectJourneyService.class);
        preflights = mock(ChantierPreflightService.class);
        notifier = mock(AutonomyNotifier.class);
        runner = new AutonomyTurnRunner(chat, liveTurns, headless, direct, store, journeys, preflights, 3);
        runner.setNotifier(notifier);
        workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn(workspaceId);
        run = ChantierAutonomyRun.builder().id(UUID.randomUUID()).userId(userId).workspaceId(workspaceId)
                .chantierNumber(2).status(AutonomyRunStatus.RUNNING).build();
        when(store.open(any(), any(), anyInt(), any(), any(), anyInt(), anyInt())).thenReturn(run);
        when(store.isRunning(run.getId())).thenReturn(true);
        when(store.finish(any(), any(), any(), any())).thenReturn(true);
        when(journeys.forTurn(userId, workspaceId)).thenAnswer(inv -> journey(plans.size() > 1 ? plans.poll()
                : plans.peek()));
        readyPreflight();
    }

    private static String step(String title, String status) {
        return "{\"title\":\"" + title + "\",\"risk\":\"REVERSIBLE\",\"status\":\"" + status + "\""
                + ("BLOQUE".equals(status) ? ",\"evidence\":\"accès refusé\"" : "") + "}";
    }

    private static String plan(String... steps) {
        return "[" + String.join(",", steps) + "]";
    }

    private SubjectJourney journey(String planJson) {
        boolean allVerified = JourneyPlan.fromJson(planJson).steps().stream()
                .allMatch(s -> s.status() == JourneyPlan.StepStatus.VERIFIE);
        return SubjectJourney.builder().userId(userId).workspaceId(workspaceId).mode(JourneyMode.GUIDE)
                .phase(JourneyPhase.EXECUTION).chantierNumber(2).chantierTitle("Ingress").planJson(planJson)
                .planVersion(1).validatedVersion(1).createdAt(OffsetDateTime.now())
                .closeProposedAt(allVerified ? OffsetDateTime.now() : null).build();
    }

    private void readyPreflight() {
        when(preflights.forOwned(userId, workspace)).thenReturn(List.of(new ChantierPreflightService.ChantierReadiness(
                2, "Ingress", new ChantierPreflight(2, true, List.of()))));
    }

    private static AtelierChatResult result(String cost) {
        return new AtelierChatResult("ok", List.of(), UUID.randomUUID(), 10, 5, 3, false, new BigDecimal(cost), null);
    }

    private ChantierAutonomyRun go() {
        LiveTurn turn = runner.prepare(userId, workspaceId, "demande").orElseThrow();
        return runner.launch(turn, workspace, 2, ChantierSnapshot.of(journey(plans.peek()), 2));
    }

    @Test
    @DisplayName("deux tours : le 1er exécute, le 2e vérifie tout → DONE, coût cumulé, notification")
    void doneInTwoTurns() {
        plans.add(plan(step("A", "A_FAIRE"), step("B", "A_FAIRE")));
        plans.add(plan(step("A", "FAIT"), step("B", "FAIT")));
        plans.add(plan(step("A", "VERIFIE"), step("B", "VERIFIE")));
        when(chat.chatStreaming(eq(userId), eq(workspaceId), anyString(), any()))
                .thenReturn(result("0.40"), result("0.35"));
        go();
        ArgumentCaptor<String> demands = ArgumentCaptor.forClass(String.class);
        verify(chat, times(2)).chatStreaming(eq(userId), eq(workspaceId), demands.capture(), any());
        assertThat(demands.getAllValues().get(0)).contains("Chantier 2 « Ingress »").contains("chantier: 2")
                .contains("AUCUNE question").contains("status=BLOQUE").contains("1. [à faire] A")
                .contains("tour 1/3");
        assertThat(demands.getAllValues().get(1)).startsWith("Poursuis.").contains("tour 2/3")
                .contains("1. [faite, à vérifier] A");
        verify(store).finish(eq(run.getId()), eq(AutonomyRunStatus.DONE), anyString(), eq(null));
        verify(store).heartbeat(eq(run.getId()), eq(2), eq(new BigDecimal("0.75")), eq(2), eq(2));
        verify(notifier).notify(userId, workspaceId, AutonomyRunStatus.DONE, false);
        verify(chat).muteOutcomePush(userId, workspaceId);
        verify(chat).unmuteOutcomePush(userId, workspaceId);
        assertThat(liveTurns.find(userId, workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("l'agent bloque une étape : BLOCKED avec la raison du préflight, aucun tour de plus")
    void blocked() {
        plans.add(plan(step("A", "A_FAIRE")));
        plans.add(plan(step("A", "BLOQUE")));
        when(preflights.forOwned(userId, workspace)).thenReturn(List.of(new ChantierPreflightService.ChantierReadiness(
                2, "Ingress", new ChantierPreflight(2, false, List.of(new ChantierPreflight.Reason(
                        ChantierPreflight.Code.STEP_BLOCKED, "Étape 1 bloquée : accès refusé", 1))))));
        when(chat.chatStreaming(eq(userId), eq(workspaceId), anyString(), any())).thenReturn(result("0.10"));
        go();
        verify(chat, times(1)).chatStreaming(any(), any(), anyString(), any());
        verify(store).finish(run.getId(), AutonomyRunStatus.BLOCKED, "Étape 1 bloquée : accès refusé", null);
        verify(notifier).notify(userId, workspaceId, AutonomyRunStatus.BLOCKED, false);
    }

    @Test
    @DisplayName("un tour sans aucune avancée : BLOCKED « Aucune avancée »")
    void noProgress() {
        plans.add(plan(step("A", "A_FAIRE")));
        when(chat.chatStreaming(eq(userId), eq(workspaceId), anyString(), any())).thenReturn(result("0.10"));
        go();
        verify(chat, times(1)).chatStreaming(any(), any(), anyString(), any());
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(store).finish(eq(run.getId()), eq(AutonomyRunStatus.BLOCKED), reason.capture(), eq(null));
        assertThat(reason.getValue()).startsWith("Aucune avancée");
    }

    @Test
    @DisplayName("plafond de tours : STOPPED, notifié")
    void maxTurns() {
        String[] subs = {"[]", "[{\"title\":\"x\",\"done\":true}]",
                "[{\"title\":\"x\",\"done\":true},{\"title\":\"y\",\"done\":true}]",
                "[{\"title\":\"x\",\"done\":true},{\"title\":\"y\",\"done\":true},{\"title\":\"z\",\"done\":true}]"};
        plans.add(withSubs(subs[0]));
        plans.add(withSubs(subs[1]));
        plans.add(withSubs(subs[2]));
        plans.add(withSubs(subs[3]));
        when(chat.chatStreaming(eq(userId), eq(workspaceId), anyString(), any())).thenReturn(result("0.10"));
        go();
        verify(chat, times(3)).chatStreaming(any(), any(), anyString(), any());
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(store).finish(eq(run.getId()), eq(AutonomyRunStatus.STOPPED), reason.capture(), eq(null));
        assertThat(reason.getValue()).startsWith("Plafond de 3 tours");
        verify(notifier).notify(userId, workspaceId, AutonomyRunStatus.STOPPED, false);
    }

    private static String withSubs(String subs) {
        return "[{\"title\":\"A\",\"risk\":\"REVERSIBLE\",\"status\":\"A_FAIRE\",\"subSteps\":" + subs + "}]";
    }

    @Test
    @DisplayName("reprise en main : la course est déjà close, la boucle ne réécrit rien et ne notifie pas")
    void takenBack() {
        plans.add(plan(step("A", "A_FAIRE")));
        when(chat.chatStreaming(eq(userId), eq(workspaceId), anyString(), any())).thenReturn(result("0.10"));
        when(store.isRunning(run.getId())).thenReturn(false);
        when(store.finish(any(), any(), any(), any())).thenReturn(false);
        go();
        verify(store).finish(run.getId(), AutonomyRunStatus.STOPPED, AutonomyTurnRunner.TAKEN_BACK, null);
        verify(notifier, never()).notify(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("erreur fournisseur : FAILED avec son code, notifiée")
    void providerError() {
        plans.add(plan(step("A", "A_FAIRE")));
        when(chat.chatStreaming(eq(userId), eq(workspaceId), anyString(), any()))
                .thenThrow(new AIProviderUnavailableException("down"));
        go();
        verify(store).finish(eq(run.getId()), eq(AutonomyRunStatus.FAILED), anyString(), eq("provider_unavailable"));
        verify(notifier).notify(userId, workspaceId, AutonomyRunStatus.FAILED, false);
        verify(chat).unmuteOutcomePush(userId, workspaceId);
    }

    @Test
    @DisplayName("la consigne de suite reprend les précisions de l'utilisateur")
    void continuationCarriesNotes() {
        String demand = AutonomyTurnRunner.continuation(3, null, JourneyPlan.fromJson(plan(step("A", "VERIFIE"),
                step("B", "A_FAIRE"))), 2, 8, List.of("utilise la branche hotfix"));
        assertThat(demand).startsWith("Précisions de l'utilisateur").contains("- utilise la branche hotfix")
                .contains("Chantier 3 —").contains("2. [à faire] B").doesNotContain("1. [");
    }
}
