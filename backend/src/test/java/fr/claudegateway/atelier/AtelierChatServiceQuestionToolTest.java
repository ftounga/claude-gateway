package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.pages.PageToolCatalog;
import fr.claudegateway.pages.PageToolExecutor;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.radar.RadarToolCatalog;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.teams.TeamsToolCatalog;

/**
 * <b>L'outil {@code demander} dans la boucle</b> (F-164 / SF-164-01) : déclaration, suspension/reprise
 * du tour, pauses répétées, validation, et la consigne système.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceQuestionToolTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private RunnerCallDispatcher runnerCallDispatcher;
    @Mock private RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private SpaceEntitlementService entitlements;
    @Mock private PageToolExecutor executor;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private Listener listener;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        listener = new Listener();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, new RunnerConfirmationGate(500L), runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true),
                AtelierCheckpointRunner.none(), ProjectRulesSource.NONE, TeamsToolCatalog.none(), null,
                RadarToolCatalog.none(), null, new PageToolCatalog(entitlements), executor);

        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
    }

    private Workspace terminal() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.LOCAL);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    private String snapshots() {
        return String.join("\n", agentProvider.messageSnapshots);
    }

    @Test
    @DisplayName("CA1 — l'outil demander est déclaré au modèle")
    void toolIsDeclared() {
        List<String> names = service.buildTools(userId, terminal()).stream()
                .map(AgentTool::name).toList();
        assertThat(names).contains("demander");
    }

    @Test
    @DisplayName("CA10 — la consigne système porte la règle par défaut, le signal manuel et l'anti-spam")
    void systemPromptCarriesTheDoctrine() {
        String prompt = service.buildSystemPrompt(userId, terminal());
        assertThat(prompt)
                .contains("réponses PROPOSABLES")
                .contains("pose-moi les questions")
                .contains("vraiment bloqué");
    }

    @Test
    @DisplayName("CA2 — un appel valide suspend le tour et rend les réponses au modèle")
    void aValidCallSuspendsAndResumesWithAnswers() {
        terminal();
        agentProvider.enqueueToolCallWithObject("demander",
                "{\"questions\":[{\"question\":\"A ou B ?\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}]}");
        agentProvider.enqueueFinal("Merci.");

        service.chatStreaming(userId, workspaceId, "aide-moi à choisir", listener);

        assertThat(listener.requests).hasSize(1);
        // La réponse composée est renvoyée au modèle au tour suivant.
        assertThat(snapshots()).contains("Réponses de l'utilisateur").contains("A");
    }

    @Test
    @DisplayName("CA3 — l'outil est appelable plusieurs fois dans un même tour (pauses répétées)")
    void repeatedPausesInASingleTurn() {
        terminal();
        agentProvider.enqueueToolCallWithObject("demander",
                "{\"questions\":[{\"question\":\"première ?\",\"options\":[{\"label\":\"A\"}]}]}");
        agentProvider.enqueueToolCallWithObject("demander",
                "{\"questions\":[{\"question\":\"seconde ?\",\"options\":[{\"label\":\"B\"}]}]}");
        agentProvider.enqueueFinal("Fini.");

        service.chatStreaming(userId, workspaceId, "pose-moi deux questions", listener);

        assertThat(listener.requests).hasSize(2);
        // Les deux réponses distinctes sont bien parvenues au modèle : deux pauses, deux reprises.
        assertThat(snapshots()).contains("rep-première ?").contains("rep-seconde ?");
    }

    @Test
    @DisplayName("CA5 — une réponse libre seule (option « autre ») est acceptée")
    void aFreeTextOnlyAnswerIsAccepted() {
        terminal();
        listener.freeTextOnly = true;
        agentProvider.enqueueToolCallWithObject("demander",
                "{\"questions\":[{\"question\":\"Autre chose ?\",\"options\":[{\"label\":\"A\"}]}]}");
        agentProvider.enqueueFinal("Noté.");

        service.chatStreaming(userId, workspaceId, "demande", listener);

        assertThat(listener.requests).hasSize(1);
        assertThat(snapshots()).contains("(réponse libre) ma réponse libre");
    }

    @Test
    @DisplayName("CA7 — isolation : answerQuestion vérifie la propriété du workspace en premier")
    void answerChecksWorkspaceOwnershipFirst() {
        UUID stranger = UUID.randomUUID();
        when(workspaceService.requireOwned(stranger, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("projet d'autrui"));

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(WorkspaceNotFoundException.class,
                () -> service.answerQuestion(stranger, workspaceId, "call",
                        List.of(new AtelierAnswerEntry("Q", List.of("A"), null))))).isNotNull();
    }

    @Test
    @DisplayName("CA8 — robustesse : une réponse à une question qui n'attend plus rien est refusée (409)")
    void aLateOrDoubleAnswerIsRejected() {
        terminal();
        // Aucune question en attente (mono-pod, diffusion inerte) : la réponse ne trouve rien à trancher.
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                fr.claudegateway.runner.exec.NoPendingConfirmationException.class,
                () -> service.answerQuestion(userId, workspaceId, "inconnu",
                        List.of(new AtelierAnswerEntry("Q", List.of("A"), null))))).isNotNull();
    }

    @Test
    @DisplayName("CA4 — un lot invalide rend une erreur au modèle sans suspendre le tour")
    void anInvalidBatchIsRejectedWithoutSuspending() {
        terminal();
        agentProvider.enqueueToolCallWithObject("demander", "{\"questions\":[]}");
        agentProvider.enqueueFinal("Bon, je décide.");

        service.chatStreaming(userId, workspaceId, "demande vide", listener);

        // Aucune question posée à l'écran (rejet avant l'attente) ; le tour a continué jusqu'au final.
        assertThat(listener.requests).isEmpty();
        assertThat(snapshots()).contains("au moins une question");
    }

    @Test
    @DisplayName("SF-164-05 — demander pousse « une question vous attend » une seule fois par appel, même pour un lot")
    void demanderPushesOneNotificationPerCall() {
        terminal();
        fr.claudegateway.push.PushNotificationService push =
                org.mockito.Mockito.mock(fr.claudegateway.push.PushNotificationService.class);
        service.setPushNotificationService(push);
        agentProvider.enqueueToolCallWithObject("demander",
                "{\"questions\":[{\"question\":\"A ou B ?\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]},"
                        + "{\"question\":\"C ou D ?\",\"options\":[{\"label\":\"C\"},{\"label\":\"D\"}]}]}");
        agentProvider.enqueueFinal("Merci.");

        service.chatStreaming(userId, workspaceId, "aide-moi à choisir", listener);

        org.mockito.Mockito.verify(push, org.mockito.Mockito.times(1)).notifyQuestionAsked(userId, workspaceId);
    }

    @Test
    @DisplayName("SF-164-05 — un autre outil (set_plan) ne pousse pas « une question vous attend »")
    void otherToolsDoNotPushTheQuestionNotification() {
        terminal();
        fr.claudegateway.push.PushNotificationService push =
                org.mockito.Mockito.mock(fr.claudegateway.push.PushNotificationService.class);
        service.setPushNotificationService(push);
        agentProvider.enqueueToolCallWithObject("set_plan", "{\"steps\":[{\"title\":\"Étape 1\",\"status\":\"active\"}]}");
        agentProvider.enqueueFinal("En cours.");

        service.chatStreaming(userId, workspaceId, "corrige le bug", listener);

        org.mockito.Mockito.verify(push, org.mockito.Mockito.never()).notifyQuestionAsked(any(), any());
    }

    @Test
    @DisplayName("SF-164-05 — un lot invalide (rejeté avant l'attente) ne pousse rien ; sans émetteur, pas d'erreur")
    void anInvalidBatchDoesNotPushAndNullEmitterIsSafe() {
        terminal();
        fr.claudegateway.push.PushNotificationService push =
                org.mockito.Mockito.mock(fr.claudegateway.push.PushNotificationService.class);
        service.setPushNotificationService(push);
        agentProvider.enqueueToolCallWithObject("demander", "{\"questions\":[]}");
        agentProvider.enqueueFinal("Bon, je décide.");

        service.chatStreaming(userId, workspaceId, "demande vide", listener);

        org.mockito.Mockito.verify(push, org.mockito.Mockito.never()).notifyQuestionAsked(any(), any());
    }

    private final class Listener implements AtelierProgressListener {

        private final List<AtelierQuestionRequest> requests = new ArrayList<>();
        private boolean freeTextOnly;

        @Override
        public void onAction(AtelierStepEvent step) {
            // rien
        }

        @Override
        public void onText(String text) {
            // rien
        }

        @Override
        public void onQuestion(AtelierQuestionRequest request) {
            requests.add(request);
            List<AtelierAnswerEntry> entries = request.form().questions().stream()
                    .map(q -> freeTextOnly
                            ? new AtelierAnswerEntry(q.header(), List.of(), "ma réponse libre")
                            : new AtelierAnswerEntry(q.header(), List.of(q.options().get(0).label()),
                                    "rep-" + q.question()))
                    .toList();
            service.answerQuestion(userId, workspaceId, request.callId(), entries);
        }
    }
}
