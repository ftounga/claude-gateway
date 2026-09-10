package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * F-51 / SF-51-04 — les règles d'un paquet actif rejoignent la <b>consigne système</b> du tour.
 *
 * <p>Deux choses sont vérifiées ici : le bloc apparaît bien <b>après</b> les conventions du projet
 * (arbitrage C1 — ce que l'utilisateur a écrit pour ce projet reste ce qu'on lit en premier), et sans
 * source de règles la consigne est celle d'avant F-51, à l'octet près.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceGovernanceRulesTest {

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
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;

    private StubAiAgentProvider agentProvider;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of("CLAUDE.md"));
        when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenReturn("# Conventions maison");

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

    private AtelierChatService serviceWith(ProjectRulesSource rules) {
        return new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient,
                        new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true),
                AtelierCheckpointRunner.none(), rules);
    }

    @Test
    @DisplayName("les règles actives rejoignent la consigne, après les conventions du projet")
    void rulesFollowTheProjectConventions() {
        AtelierChatService service = serviceWith(
                (user, workspace) -> "## Livrables\nAucune trace de LLM.");
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "salut");

        String system = agentProvider.lastRequest.system();
        assertThat(system).contains(AtelierChatService.GOVERNANCE_HEADER)
                .contains("## Livrables")
                .contains("Aucune trace de LLM.");
        assertThat(system.indexOf("# Conventions maison"))
                .isLessThan(system.indexOf(AtelierChatService.GOVERNANCE_HEADER));
    }

    @Test
    @DisplayName("sans source de règles, la consigne est celle d'avant F-51")
    void withoutRulesTheSystemPromptIsUnchanged() {
        AtelierChatService service = serviceWith(ProjectRulesSource.NONE);
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "salut");

        assertThat(agentProvider.lastRequest.system())
                .doesNotContain(AtelierChatService.GOVERNANCE_HEADER)
                .contains("# Conventions maison");
    }

    @Test
    @DisplayName("une source de règles qui échoue ne fait pas rater le tour")
    void failingRulesSourceIsIgnored() {
        AtelierChatService service = serviceWith((user, workspace) -> {
            throw new IllegalStateException("base indisponible");
        });
        agentProvider.enqueueFinal("Bonjour.");

        AtelierChatService.AtelierChatResult result = service.chat(userId, workspaceId, "salut");

        assertThat(result.reply()).isEqualTo("Bonjour.");
        assertThat(agentProvider.lastRequest.system())
                .doesNotContain(AtelierChatService.GOVERNANCE_HEADER);
    }

    @Test
    @DisplayName("des règles blanches n'ajoutent aucun titre")
    void blankRulesAddNothing() {
        AtelierChatService service = serviceWith((user, workspace) -> "   ");
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "salut");

        assertThat(agentProvider.lastRequest.system())
                .doesNotContain(AtelierChatService.GOVERNANCE_HEADER);
    }

    @Test
    @DisplayName("les règles sont demandées pour le couple (utilisateur, projet) du tour")
    void rulesAreScopedToTheTurn() {
        java.util.List<String> asked = new java.util.ArrayList<>();
        AtelierChatService service = serviceWith((user, workspace) -> {
            asked.add(user + ":" + workspace);
            return null;
        });
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "salut");

        assertThat(asked).containsExactly(userId + ":" + workspaceId);
    }
}
