package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
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
 * <b>Le bloc de passation</b> (F-179 / SF-179-01) : l'outil {@code ouvrir_sujet} du terminal du poste,
 * sa résolution isolée (user_id + poste), le relais au fil de l'eau et la transcription.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceHandoffTest {

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
    @Mock private PageToolExecutor pageExecutor;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private final List<SubjectHandoff> handoffs = new ArrayList<>();
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private Workspace terminal;

    private final AtelierProgressListener listener = new AtelierProgressListener() {
        @Override
        public void onHandoff(String toolUseId, SubjectHandoff handoff) {
            handoffs.add(handoff);
        }

        @Override
        public void onAction(AtelierStepEvent step) {
        }

        @Override
        public void onText(String text) {
        }
    };

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, new RunnerConfirmationGate(200L), runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true),
                AtelierCheckpointRunner.none(), ProjectRulesSource.NONE, TeamsToolCatalog.none(), null,
                RadarToolCatalog.none(), null, new PageToolCatalog(entitlements), pageExecutor);

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

        terminal = new Workspace();
        terminal.setId(workspaceId);
        terminal.setUserId(userId);
        terminal.setHostId(hostId);
        terminal.setHostTerminal(true);
        terminal.setName("Terminal du poste");
        terminal.setSource(WorkspaceSource.LOCAL);
        terminal.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(terminal);
    }

    private Workspace subject(String name, UUID host) {
        Workspace w = new Workspace();
        w.setId(UUID.randomUUID());
        w.setUserId(userId);
        w.setHostId(host);
        w.setName(name);
        w.setProjectPath(name);
        w.setSource(WorkspaceSource.LOCAL);
        w.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        return w;
    }

    private String savedTranscripts() {
        org.mockito.ArgumentCaptor<AtelierMessage> saved = org.mockito.ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues().stream().map(AtelierMessage::getTerminalJson)
                .filter(java.util.Objects::nonNull).reduce("", String::concat);
    }

    private void callOpen(String sujet, String phrase) {
        agentProvider.enqueueToolCall(AtelierChatService.OPEN_SUBJECT_TOOL_NAME, "sujet", sujet, "phrase", phrase);
        agentProvider.enqueueFinal("Je t'ouvre le sujet.");
        service.chatStreaming(userId, workspaceId, "go", listener);
    }

    @Test
    @DisplayName("L'outil ouvrir_sujet est offert au terminal du poste")
    void offeredOnTheHostTerminal() {
        agentProvider.enqueueFinal("ok");
        service.chatStreaming(userId, workspaceId, "bonjour", listener);

        assertThat(agentProvider.lastRequest.tools().stream().map(fr.claudegateway.agent.AgentTool::name))
                .contains(AtelierChatService.OPEN_SUBJECT_TOOL_NAME);
        assertThat(agentProvider.lastRequest.system()).contains("APPELLE ouvrir_sujet");
    }

    @Test
    @DisplayName("L'outil ouvrir_sujet est absent d'un terminal de projet, et un appel forcé ne pose rien")
    void absentOnAProject() {
        terminal.setHostTerminal(false);
        terminal.setProjectPath("projet");
        agentProvider.enqueueToolCall(AtelierChatService.OPEN_SUBJECT_TOOL_NAME, "sujet", "x", "phrase", "y");
        agentProvider.enqueueFinal("ok");
        service.chatStreaming(userId, workspaceId, "go", listener);

        assertThat(agentProvider.lastRequest.tools().stream().map(fr.claudegateway.agent.AgentTool::name))
                .doesNotContain(AtelierChatService.OPEN_SUBJECT_TOOL_NAME);
        assertThat(agentProvider.lastRequest.system()).doesNotContain("APPELLE ouvrir_sujet");
        assertThat(handoffs).isEmpty();
    }

    @Test
    @DisplayName("Par nom : le sujet du poste est résolu, relayé au fil de l'eau et rangé dans la transcription")
    void byNameIsRelayedAndPersisted() {
        Workspace dp = subject("data-platform", hostId);
        when(workspaceService.listByHost(userId, hostId)).thenReturn(List.of(subject("lzi", hostId), dp));

        callOpen("Data-Platform/", "Reprends data-platform : lis le PLAN-ACTION.md");

        assertThat(handoffs).hasSize(1);
        assertThat(handoffs.get(0).workspaceId()).isEqualTo(dp.getId());
        assertThat(handoffs.get(0).name()).isEqualTo("data-platform");
        assertThat(handoffs.get(0).phrase()).isEqualTo("Reprends data-platform : lis le PLAN-ACTION.md");
        assertThat(savedTranscripts()).contains("\"handoff\":{\"workspaceId\":\"" + dp.getId() + "\"");
    }

    @Test
    @DisplayName("Par id : un sujet du même poste est accepté")
    void byIdOfTheSameHost() {
        Workspace dp = subject("data-platform", hostId);
        when(workspaceService.requireOwned(userId, dp.getId())).thenReturn(dp);

        callOpen(dp.getId().toString(), "Reprends.");

        assertThat(handoffs).extracting(SubjectHandoff::workspaceId).containsExactly(dp.getId());
    }

    @Test
    @DisplayName("Isolation : un sujet d'un AUTRE poste ne peut pas être ciblé")
    void anotherHostIsRefused() {
        Workspace other = subject("data-platform", UUID.randomUUID());
        when(workspaceService.requireOwned(userId, other.getId())).thenReturn(other);

        callOpen(other.getId().toString(), "Reprends.");

        assertThat(handoffs).isEmpty();
        assertThat(savedTranscripts()).contains("pas un sujet de ce poste").doesNotContain("\"handoff\":{");
    }

    @Test
    @DisplayName("Isolation : un id d'un AUTRE utilisateur est introuvable (requireOwned)")
    void anotherUserIsRefused() {
        UUID foreign = UUID.randomUUID();
        when(workspaceService.requireOwned(userId, foreign))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable : " + foreign));

        callOpen(foreign.toString(), "Reprends.");

        assertThat(handoffs).isEmpty();
    }

    @Test
    @DisplayName("Le terminal du poste lui-même n'est pas un sujet")
    void theHostTerminalIsNotASubject() {
        Workspace self = subject("racine", hostId);
        self.setHostTerminal(true);
        when(workspaceService.requireOwned(userId, self.getId())).thenReturn(self);

        callOpen(self.getId().toString(), "Reprends.");

        assertThat(handoffs).isEmpty();
    }

    @Test
    @DisplayName("Nom ambigu, nom inconnu, phrase vide : rien n'est posé")
    void ambiguousUnknownOrBlank() {
        when(workspaceService.listByHost(userId, hostId))
                .thenReturn(List.of(subject("dp", hostId), subject("DP", hostId)));
        callOpen("dp", "Reprends.");
        assertThat(handoffs).isEmpty();
        assertThat(savedTranscripts()).contains("Plusieurs sujets");

        callOpen("inconnu", "Reprends.");
        assertThat(handoffs).isEmpty();

        callOpen("dp", "   ");
        assertThat(handoffs).isEmpty();
    }

    @Test
    @DisplayName("create_subject rend l'id du sujet créé")
    void createSubjectReturnsTheId() {
        Workspace created = subject("data-platform", hostId);
        when(workspaceService.openOnHost(userId, hostId, "data-platform", "Terminal du poste")).thenReturn(created);
        agentProvider.enqueueToolCall("create_subject", "name", "data-platform");
        agentProvider.enqueueFinal("créé");

        service.chatStreaming(userId, workspaceId, "crée data-platform", listener);

        assertThat(savedTranscripts()).contains("id : " + created.getId());
    }

    @Test
    @DisplayName("Le bornage d'une transcription conserve le bloc de passation")
    void boundingKeepsTheHandoff() {
        SubjectHandoff handoff = new SubjectHandoff(UUID.randomUUID(), "data-platform", "Reprends.");
        AtelierTurnReport.Block block = new AtelierTurnReport.Block("ouvrir_sujet", "x", "c", null,
                "y".repeat(AtelierTurnReport.MAX_BLOCK_OUTPUT_CHARS + 10), true, false, false, null, null, null,
                null, handoff);

        String json = new AtelierTurnReport(10, 10, 1, false, false, AtelierPlan.EMPTY, List.of(block)).toJson();

        assertThat(json).contains("début tronqué").contains("\"handoff\":{").contains("\"phrase\":\"Reprends.\"");
    }
}
