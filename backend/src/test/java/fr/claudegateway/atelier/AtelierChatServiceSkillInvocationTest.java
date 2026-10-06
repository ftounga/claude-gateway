package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.skills.SkillCatalogService;
import fr.claudegateway.atelier.skills.SkillEntry;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * F-177 / SF-177-03 — {@code /nom texte} charge le skill de façon déterministe (dans le message, jamais
 * dans la consigne système) ; l'outil {@code skill} est offert hors Teams.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceSkillInvocationTest {

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
    @Mock private SkillCatalogService skills;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private Workspace workspace;

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
        service.setSkillCatalog(skills);
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
        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
    }

    @Test
    void slashSkillJoinsTheSkillToTheMessageNotTheSystem() {
        SkillEntry entry = new SkillEntry("ticket-jira", ".claude/skills/ticket-jira.md", "", "POSTE");
        when(skills.load(eq(userId), eq(workspace), eq("ticket-jira")))
                .thenReturn(Optional.of(new SkillCatalogService.LoadedSkill(entry, "ÉTAPES_DU_TICKET")));
        agentProvider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "/ticket-jira DECPB-200");

        String messages = String.valueOf(agentProvider.lastRequest.messages());
        assertThat(messages).contains(AtelierChatService.INVOKED_SKILL_HEADER + "ticket-jira")
                .contains("ÉTAPES_DU_TICKET").contains("/ticket-jira DECPB-200");
        assertThat(agentProvider.lastRequest.system()).doesNotContain("ÉTAPES_DU_TICKET");
    }

    @Test
    void anUnknownSlashLeavesTheMessageUntouched() {
        when(skills.load(any(), any(), any())).thenReturn(Optional.empty());
        agentProvider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "/inconnu fais ceci");

        assertThat(String.valueOf(agentProvider.lastRequest.messages()))
                .doesNotContain(AtelierChatService.INVOKED_SKILL_HEADER);
    }

    @Test
    void theSkillToolIsOfferedOutsideTeams() {
        agentProvider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "bonjour");
        assertThat(agentProvider.lastRequest.tools()).extracting(AgentTool::name)
                .contains(AtelierChatService.SKILL_TOOL_NAME);
    }

    @Test
    void theSkillToolReturnsTheContent() {
        SkillEntry entry = new SkillEntry("deploy", ".claude/skills/deploy.md", "", "SUJET");
        when(skills.load(eq(userId), eq(workspace), eq("deploy")))
                .thenReturn(Optional.of(new SkillCatalogService.LoadedSkill(entry, "CONTENU_DEPLOY")));
        agentProvider.enqueueToolCall(AtelierChatService.SKILL_TOOL_NAME, "nom", "deploy");
        agentProvider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "déploie");

        assertThat(String.valueOf(agentProvider.lastRequest.messages())).contains("CONTENU_DEPLOY");
    }
}
