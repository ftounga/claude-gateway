package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.UsageSnapshot;

/** L'outil serveur {@code carte_chercher} dans la boucle (F-174 / SF-174-05, D8). */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceMapSearchToolTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private HostKnowledgeSource knowledge;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService,
                        mock(fr.claudegateway.git.GitTokenService.class),
                        mock(fr.claudegateway.git.GitHubClient.class),
                        new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                mock(fr.claudegateway.runner.exec.RunnerToolGateway.class),
                mock(fr.claudegateway.runner.channel.RunnerCallDispatcher.class),
                mock(fr.claudegateway.runner.exec.RunnerConfirmationGate.class),
                mock(fr.claudegateway.runner.audit.RunnerAuditService.class),
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                mock(fr.claudegateway.runner.host.RunnerHostService.class),
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true),
                fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner.none(),
                null, null, null, null, null, null, null, knowledge);

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(quotaService.currentUsage(userId))
                .thenReturn(new UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        org.mockito.Mockito.lenient().when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(
                workspaceId, userId)).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        org.mockito.Mockito.lenient().when(workspaceService.tree(any(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(workspaceService.readFile(any(), any(), any()))
                .thenThrow(new InvalidFilePathException("absent"));
    }

    @Test
    @DisplayName("offert sur un poste réel : l'agent l'appelle, la gateway répond depuis l'index")
    void declaredAndAnsweredByTheGateway() {
        when(knowledge.mapSearchAvailable(userId, workspaceId)).thenReturn(true);
        when(knowledge.searchMap(eq(userId), eq(workspaceId), eq("proxy websockets"), isNull(), isNull()))
                .thenReturn("Carte de ce poste — « proxy websockets » : 1 fait(s)\n- le proxy coupe les "
                        + "websockets  [acces.md § Proxy]  ⟨piège⟩\n");
        agentProvider.enqueueToolCall("carte_chercher", "requete", "proxy websockets");
        agentProvider.enqueueFinal("Le proxy coupe les websockets.");

        service.chat(userId, workspaceId, "pourquoi la console tombe ?");

        assertThat(agentProvider.toolBelts.get(0)).contains("carte_chercher");
        assertThat(agentProvider.messageSnapshots.get(1)).contains("le proxy coupe les websockets")
                .contains("⟨piège⟩");
    }

    @Test
    @DisplayName("sans poste réel ou index éteint : l'outil n'est pas offert")
    void notDeclaredWithoutMap() {
        when(knowledge.mapSearchAvailable(userId, workspaceId)).thenReturn(false);
        agentProvider.enqueueFinal("Oui.");

        service.chat(userId, workspaceId, "bonjour");

        assertThat(agentProvider.toolBelts.get(0)).doesNotContain("carte_chercher");
    }

    @Test
    @DisplayName("un appel sans argument est refusé sans interroger la carte")
    void emptyCallIsRefused() {
        when(knowledge.mapSearchAvailable(userId, workspaceId)).thenReturn(true);
        agentProvider.enqueueToolCall("carte_chercher");
        agentProvider.enqueueFinal("Je précise.");

        service.chat(userId, workspaceId, "et la carte ?");

        verify(knowledge, never()).searchMap(any(), any(), any(), any(), any());
        assertThat(agentProvider.messageSnapshots.get(1)).contains("donne une requête");
    }
}
