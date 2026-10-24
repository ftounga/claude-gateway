package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.journey.JourneyMode;
import fr.claudegateway.atelier.journey.JourneyPhase;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyService;
import fr.claudegateway.atelier.proof.ProofCapture;
import fr.claudegateway.atelier.proof.ProofCaptureRepository;
import fr.claudegateway.atelier.proof.ProofCaptureService;
import fr.claudegateway.atelier.proof.ProofToolCatalog;
import fr.claudegateway.atelier.proof.TerminalShotRenderer;
import fr.claudegateway.atelier.storage.InMemoryWorkspaceStorage;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Les captures de preuve (F-212 / SF-212-03) dans la boucle : déclaration, porte du parcours, invite
 * d'autorisation PAR COMMANDE (même porte que bash), « mettez à jour le poste », et coût — aucune
 * image ne revient au modèle, le texte est borné.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceProofTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    @Mock private ProofCaptureRepository proofRepository;
    @Mock private SubjectJourneyService journeyService;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private Workspace workspace;
    private final List<String> asked = new ArrayList<>();
    private final AtomicInteger denyFrom = new AtomicInteger(Integer.MAX_VALUE);

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));
        TerminalShotRenderer renderer = new TerminalShotRenderer() {
            @Override
            public List<Shot> shoot(JsonNode session) {
                return List.of(new Shot(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, 1600, 700));
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
        service.setProofCaptureService(new ProofCaptureService(proofRepository, new InMemoryWorkspaceStorage(),
                renderer, MAPPER));
        service.setJourneyService(journeyService);

        when(confirmationGate.await(any(), any(), anyString(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            asked.add(invocation.getArgument(2, String.class));
            return asked.size() >= denyFrom.get()
                    ? new RunnerConfirmationGate.Outcome(RunnerConfirmationGate.Decision.DENY, null)
                    : new RunnerConfirmationGate.Outcome(RunnerConfirmationGate.Decision.ALLOW, null);
        });
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
        when(proofRepository.save(any(ProofCapture.class))).thenAnswer(invocation -> invocation.getArgument(0));

        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.LOCAL);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        workspace.setAgentAskBeforeBash(true);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(runnerHostService.declaredCapabilities(hostId)).thenReturn(Set.of("files", "bash", "proof"));
    }

    private static String report(int commands, String stdout) {
        StringBuilder json = new StringBuilder("{\"os\":\"linux\",\"user\":\"franck\",\"host\":\"pc\",\"home\":\"/home/franck\","
                + "\"execShell\":\"bash\",\"cwd\":\"/home/franck/projet\",\"commands\":[");
        for (int i = 0; i < commands; i++) {
            json.append(i == 0 ? "" : ",").append("{\"index\":").append(i + 1)
                    .append(",\"command\":\"c").append(i + 1).append("\",\"status\":\"done\",\"exitCode\":0,")
                    .append("\"durationMs\":1200,\"stdout\":").append(MAPPER.valueToTree(stdout).toString()).append(",\"stderr\":\"\"}");
        }
        return json.append("]}").toString();
    }

    private void runnerAnswers(String content) {
        when(runnerToolGateway.proof(any(), anyString(), any(), anyLong()))
                .thenReturn(new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false));
    }

    private AgentContentBlock.ToolResult lastToolResult() {
        AgentContentBlock.ToolResult found = null;
        for (AgentMessage message : agentProvider.lastRequest.messages()) {
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.ToolResult result) {
                    found = result;
                }
            }
        }
        assertThat(found).isNotNull();
        return found;
    }

    private void callProof(String commandsJson) {
        agentProvider.enqueueToolCallWithObject(ProofToolCatalog.TOOL,
                "{\"titre\":\"Le service répond\",\"commandes\":" + commandsJson + "}");
        agentProvider.enqueueFinal("C'est prouvé.");
        service.chat(userId, workspaceId, "montre que ça marche");
    }

    @Test
    @DisplayName("déclaré sur un poste, jamais sur un terminal hébergé ; définition courte et fixe")
    void declaration() {
        List<String> names = service.buildTools(userId, workspace).stream().map(AgentTool::name).toList();
        assertThat(names).contains(ProofToolCatalog.TOOL);
        AgentTool tool = service.buildTools(userId, workspace).stream()
                .filter(t -> t.name().equals(ProofToolCatalog.TOOL)).findFirst().orElseThrow();
        // Coût : < 250 tokens (≈ 4 caractères par token) pour la définition.
        assertThat(tool.description().length() + tool.inputSchema().toString().length()).isLessThan(1000);
        assertThat(service.buildTools(userId, workspace)).contains(ProofToolCatalog.tool());

        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        assertThat(service.buildTools(userId, workspace).stream().map(AgentTool::name))
                .doesNotContain(ProofToolCatalog.TOOL);
    }

    @Test
    @DisplayName("chaque commande passe l'invite d'autorisation comme un bash, puis une seule session")
    void everyCommandIsAskedThenOneSession() {
        runnerAnswers(report(2, "ok\n"));
        callProof("[\"curl -s localhost/health\",\"git status\"]");

        assertThat(asked).hasSize(2);
        assertThat(asked.get(0)).endsWith("-1");
        assertThat(asked.get(1)).endsWith("-2");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> sent = ArgumentCaptor.forClass(List.class);
        verify(runnerToolGateway, times(1)).proof(any(), anyString(), sent.capture(), anyLong());
        assertThat(sent.getValue()).containsExactly("curl -s localhost/health", "git status");
        // Journal : une ligne par commande, comme un bash.
        verify(runnerAuditService, times(2)).recordCall(eq(userId), any(), anyString(), eq("bash"), anyString(), any());
    }

    @Test
    @DisplayName("une commande refusée : rien n'est exécuté, le modèle le sait")
    void oneDeniedNothingRuns() {
        denyFrom.set(2);
        runnerAnswers(report(2, "ok\n"));
        callProof("[\"ls\",\"rm -rf build\"]");

        verify(runnerToolGateway, never()).proof(any(), anyString(), any(), anyLong());
        assertThat(lastToolResult().content()).contains("Commande 2").contains("Aucune commande n'a été exécutée");
        assertThat(lastToolResult().isError()).isTrue();
    }

    @Test
    @DisplayName("la porte du parcours guidé classe la session par sa commande la plus risquée")
    void journeyGateAppliesToTheRiskiestCommand() {
        when(journeyService.forTurn(userId, workspaceId)).thenReturn(SubjectJourney.builder()
                .userId(userId).workspaceId(workspaceId).mode(JourneyMode.GUIDE).phase(JourneyPhase.INVESTIGATION)
                .validatedVersion(null).planVersion(0).build());
        runnerAnswers(report(2, "ok\n"));
        callProof("[\"ls\",\"kubectl apply -f ingress.yaml\"]");

        verify(runnerToolGateway, never()).proof(any(), anyString(), any(), anyLong());
        assertThat(asked).isEmpty();
        verify(journeyService).recordGateBlocked(any(), eq(fr.claudegateway.atelier.journey.JourneyPlan.Risk.EXTERNE),
                eq(ProofToolCatalog.TOOL));
    }

    @Test
    @DisplayName("runner ancien (sans capacité proof) : « mettez à jour le poste », rien n'est émis")
    void oldRunnerSaysUpdate() {
        when(runnerHostService.declaredCapabilities(hostId)).thenReturn(Set.of("files", "bash"));
        callProof("[\"ls\"]");

        verify(runnerToolGateway, never()).proof(any(), anyString(), any(), anyLong());
        assertThat(lastToolResult().content()).contains("mettez à jour le poste");
    }

    @Test
    @DisplayName("coût : aucune image au modèle, texte borné (≈ 2 000 par commande, 6 000 au total)")
    void noImageAndBoundedText() {
        workspace.setAgentAskBeforeBash(false);
        runnerAnswers(report(8, "x".repeat(50_000)));
        callProof("[\"c1\",\"c2\",\"c3\",\"c4\",\"c5\",\"c6\",\"c7\",\"c8\"]");

        AgentContentBlock.ToolResult result = lastToolResult();
        assertThat(result.blocks() == null || result.blocks().isEmpty()).isTrue();
        assertThat(result.content()).doesNotContain("PNG").contains("preuves=");
        assertThat(result.content().length()).isLessThan(6_000 + 1_500);
        verify(proofRepository, atLeastOnce()).save(any(ProofCapture.class));
    }

    @Test
    @DisplayName("le bloc « Captures de preuve » est écrit dans le compte rendu du tour")
    void proofBlockInTranscript() {
        workspace.setAgentAskBeforeBash(false);
        runnerAnswers(report(1, "{\"status\":\"UP\"}"));
        callProof("[\"curl -s localhost/health\"]");

        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().stream().map(AtelierMessage::getTerminalJson)
                .filter(json -> json != null && json.contains("\"proof\":{")))
                .anyMatch(json -> json.contains("\"title\":\"Le service répond\"") && json.contains("\"captures\":[{\"id\":"));
    }

    @Test
    @DisplayName("terminal hébergé : refus explicite")
    void hostedIsRefused() {
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        callProof("[\"ls\"]");
        verify(runnerToolGateway, never()).proof(any(), anyString(), any(), anyLong());
        assertThat(lastToolResult().content()).contains("poste connecté");
    }
}
