package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Mémoire de la trajectoire d'outils (F-39 / SF-39-03) : ce que le tour retient, et ce que le tour
 * suivant retrouve. Sans elle, l'agent relit au message suivant les fichiers qu'il vient de lire.
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceMemoryTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private fr.claudegateway.runner.exec.RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private Workspace workspace;
    /** Messages « déjà en base » vus par le tour courant. */
    private final List<AtelierMessage> history = new ArrayList<>();
    /** Messages persistés par le tour courant. */
    private final List<AtelierMessage> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null));

        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        // Quota lu pour dériver le plafond de consommation du message (F-39 / SF-39-15).
        org.mockito.Mockito.lenient().when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        lenient().when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(UUID.randomUUID());
            }
            saved.add(message);
            return message;
        });
        lenient().when(workspaceService.tree(any(), any())).thenReturn(List.of());
        lenient().when(workspaceService.readFile(any(), any(), any())).thenReturn("contenu");
    }

    private AtelierMessage assistantMessage(String content, String toolTrace) {
        AtelierMessage message = AtelierMessage.builder()
                .id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role("ASSISTANT").content(content).toolTrace(toolTrace).build();
        return message;
    }

    private AtelierMessage userMessage(String content) {
        return AtelierMessage.builder()
                .id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role("USER").content(content).build();
    }

    /** Trajectoire minimale, telle que le service la sérialise. */
    private String traceJson(String callId) {
        return new AtelierToolTrace(List.of(new AtelierToolTrace.Step("je lis",
                List.of(new AtelierToolTrace.Call(callId, "read_file", null, "contenu lu", false)))))
                .toJson();
    }

    private AtelierMessage lastSavedAssistant() {
        return saved.stream().filter(m -> "ASSISTANT".equals(m.getRole())).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void aTurnWithToolsRemembersWhatItDid() {
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("J'ai lu notes.txt.");

        service.chat(userId, workspaceId, "lis notes.txt");

        AtelierToolTrace trace = AtelierToolTrace.fromJson(lastSavedAssistant().getToolTrace());
        assertThat(trace.steps()).hasSize(1);
        AtelierToolTrace.Call call = trace.steps().get(0).calls().get(0);
        assertThat(call.name()).isEqualTo("read_file");
        assertThat(call.input().path("path").asText()).isEqualTo("notes.txt");
        // La lecture mémorisée est celle qui a été rendue au modèle : numérotée (SF-39-06).
        assertThat(call.result()).isEqualTo("     1→contenu\n");
        assertThat(call.error()).isFalse();
    }

    @Test
    void aTurnWithoutToolsRemembersNothing() {
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "bonjour");

        assertThat(lastSavedAssistant().getToolTrace()).isNull();
    }

    @Test
    void theNextTurnReplaysToolUseAndToolResultInOrder() {
        history.add(userMessage("lis notes.txt"));
        history.add(assistantMessage("J'ai lu notes.txt.", traceJson("call_1")));
        agentProvider.enqueueFinal("Compris.");

        service.chat(userId, workspaceId, "et maintenant ?");

        List<AgentMessage> replayed = agentProvider.lastRequest.messages();
        // user → assistant(tool_use) → user(tool_result) → assistant(texte final) → user(nouveau)
        assertThat(replayed).hasSize(5);
        assertThat(replayed.get(1).role()).isEqualTo("assistant");
        AgentContentBlock.ToolUse use = (AgentContentBlock.ToolUse) replayed.get(1).content().get(1);
        assertThat(use.name()).isEqualTo("read_file");
        AgentContentBlock.ToolResult result =
                (AgentContentBlock.ToolResult) replayed.get(2).content().get(0);
        assertThat(result.toolUseId()).isEqualTo(use.id());
        assertThat(replayed.get(3).content().get(0)).isEqualTo(new AgentContentBlock.Text("J'ai lu notes.txt."));
    }

    @Test
    void onlyTheFiveMostRecentTurnsAreReplayedWithTheirTrajectory() {
        for (int turn = 0; turn < 7; turn++) {
            history.add(userMessage("demande " + turn));
            history.add(assistantMessage("réponse " + turn, traceJson("call_" + turn)));
        }
        agentProvider.enqueueFinal("Compris.");

        service.chat(userId, workspaceId, "suite");

        List<String> replayedIds = new ArrayList<>();
        for (AgentMessage message : agentProvider.lastRequest.messages()) {
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.ToolUse use) {
                    replayedIds.add(use.id());
                }
            }
        }
        assertThat(replayedIds).containsExactly("call_2", "call_3", "call_4", "call_5", "call_6");
    }

    @Test
    void anUnreadableTrajectoryFallsBackOnTextOnlyReplay() {
        history.add(userMessage("lis notes.txt"));
        history.add(assistantMessage("J'ai lu notes.txt.", "{tronqué"));
        agentProvider.enqueueFinal("Compris.");

        service.chat(userId, workspaceId, "et maintenant ?");

        List<AgentMessage> replayed = agentProvider.lastRequest.messages();
        assertThat(replayed).hasSize(3);
        assertThat(replayed.get(1).content().get(0))
                .isEqualTo(new AgentContentBlock.Text("J'ai lu notes.txt."));
    }

    @Test
    void afterAFreshStartTheTurnReplaysNothingFromBeforeTheBoundary() {
        // SF-39-04 : « repartir à neuf » ne supprime rien — le tour suivant lit simplement à partir
        // de la frontière, et n'a donc que le nouveau message à envoyer.
        java.time.OffsetDateTime boundary = java.time.OffsetDateTime.now().minusMinutes(5);
        workspace.setChatThreadStartedAt(boundary);
        when(messageRepository.findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                workspaceId, userId, boundary)).thenReturn(List.of());
        history.add(userMessage("vieille demande"));
        history.add(assistantMessage("vieille réponse", traceJson("call_old")));
        agentProvider.enqueueFinal("Nouveau sujet.");

        service.chat(userId, workspaceId, "on reprend à zéro");

        assertThat(agentProvider.lastRequest.messages()).hasSize(1);
    }

    @Test
    void messagesFromBeforeThisSubfeatureAreReplayedExactlyAsBefore() {
        history.add(userMessage("ancienne demande"));
        history.add(assistantMessage("ancienne réponse", null));
        agentProvider.enqueueFinal("Compris.");

        service.chat(userId, workspaceId, "suite");

        assertThat(agentProvider.lastRequest.messages()).hasSize(3);
    }
}
