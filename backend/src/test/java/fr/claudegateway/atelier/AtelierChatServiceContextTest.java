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

import fr.claudegateway.agent.AgentContextPolicy;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Politique de contexte de la boucle maison (F-39 / SF-39-12) : chaque tour demande au fournisseur
 * d'écarter les résultats d'outils périmés, et le coupe-circuit de configuration la désarme.
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceContextTest {

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
    private final List<AtelierMessage> history = new ArrayList<>();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        // Isolation : le workspace est toujours résolu par (userId, workspaceId).
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        lenient().when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(UUID.randomUUID());
            }
            return message;
        });
        lenient().when(workspaceService.tree(any(), any())).thenReturn(List.of());
        lenient().when(workspaceService.readFile(any(), any(), any())).thenReturn("contenu");
    }

    private void buildService(Boolean contextPruning) {
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                new AtelierProperties(null, null, null, null, null, null, null, null, null, contextPruning));
    }

    @Test
    void asksToPruneStaleToolResultsByDefault() {
        buildService(null);
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "bonjour");

        AgentContextPolicy policy = agentProvider.lastRequest.contextPolicy();
        assertThat(policy.pruneToolResults()).isTrue();
        assertThat(policy.triggerInputTokens()).isEqualTo(200_000);
        // Ce sur quoi l'agent travaille à l'instant reste toujours là.
        assertThat(policy.keepRecentToolResults()).isEqualTo(3);
        // Plancher d'écartement : une édition invalide le cache, elle doit le mériter (D-L6-9).
        assertThat(policy.clearAtLeastInputTokens()).isEqualTo(20_000);
    }

    @Test
    void disarmsThePolicyWhenTheCircuitBreakerIsOff() {
        // Le mécanisme repose sur une capacité beta : si elle était retirée, chaque tour de
        // l'Atelier échouerait. Ce réglage rétablit le service sans livraison (D-L6-11).
        buildService(false);
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "bonjour");

        assertThat(agentProvider.lastRequest.contextPolicy()).isEqualTo(AgentContextPolicy.none());
    }
}
