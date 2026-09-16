package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.permission.AtelierPermissionRule;
import fr.claudegateway.atelier.permission.AtelierPermissionRuleRepository;
import fr.claudegateway.atelier.permission.AtelierPermissionService;
import fr.claudegateway.atelier.permission.PermissionEffect;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.audit.RunnerAuditOutcome;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Modèle de permission allow/ask/deny persisté par workspace/user (F-121 / SF-121-02), câblé dans la
 * boucle en cible RUNNER : {@code DENY} bloque avant émission, une règle {@code ALLOW} persistée
 * exécute sans redemander (elle survit au tour et au redémarrage — elle est relue à chaque tour),
 * l'« ask » s'étend aux éditions quand c'est configuré, et « toujours autoriser » écrit une règle.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServicePermissionTest {

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
    @Mock private AtelierPermissionRuleRepository permissionRepository;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final fr.claudegateway.runner.channel.RunnerTarget runnerTarget =
            new fr.claudegateway.runner.channel.RunnerTarget(hostId, workspaceId, "projet");

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
        service.setPermissionService(new AtelierPermissionService(permissionRepository));

        // Par défaut : la porte autorise (et relaie la demande), comme la vraie porte.
        when(confirmationGate.await(any(), any(), anyString(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return new RunnerConfirmationGate.Outcome(RunnerConfirmationGate.Decision.ALLOW, null);
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
        when(runnerToolGateway.listFiles(eq(runnerTarget), anyString())).thenReturn(ok(""));
        // Par défaut, aucune règle : on retombe sur les défauts (bash selon agent_ask_before_bash).
        when(permissionRepository.findByUserIdAndWorkspaceId(userId, workspaceId)).thenReturn(List.of());
    }

    private Workspace runnerWorkspace(boolean askBeforeBash) {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        workspace.setAgentAskBeforeBash(askBeforeBash);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private static RunnerCallResult bashOk(String streamed, int exitCode) {
        return new RunnerCallResult(true, "", false, exitCode, 12L, null, null, null, streamed, false);
    }

    private String lastToolResultText() {
        AgentContentBlock.ToolResult found = null;
        for (AgentMessage message : agentProvider.lastRequest.messages()) {
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.ToolResult result) {
                    found = result;
                }
            }
        }
        assertThat(found).as("aucun tool_result transmis au modèle").isNotNull();
        return found.content();
    }

    @Test
    void aDenyRuleBlocksBashBeforeEmission() {
        runnerWorkspace(false);
        when(permissionRepository.findByUserIdAndWorkspaceId(userId, workspaceId)).thenReturn(List.of(
                AtelierPermissionRule.builder().userId(userId).workspaceId(workspaceId)
                        .tool("bash").effect(PermissionEffect.DENY.name()).build()));
        agentProvider.enqueueToolCall("bash", "command", "rm -rf build");
        agentProvider.enqueueFinal("Compris.");

        service.chat(userId, workspaceId, "supprime build");

        // Rien n'est parti sur la machine, et aucune invite n'a été posée : le DENY tranche seul.
        verify(runnerToolGateway, never()).bash(any(), anyString(), anyString(), any(), anyLong(), any());
        verify(confirmationGate, never()).await(any(), any(), anyString(), any());
        verify(runnerAuditService).recordDenied(eq(userId), eq(runnerTarget), anyString(), eq("bash"),
                any(), eq(RunnerAuditOutcome.DENIED));
        assertThat(lastToolResultText()).contains("refusé par une règle");
    }

    @Test
    void aPersistedAllowRuleRunsBashWithoutConfirmation() {
        // agent_ask_before_bash = true → le défaut serait ASK ; mais une règle ALLOW persistée
        // (relue à chaque tour, donc survivant au redémarrage) exécute sans redemander.
        runnerWorkspace(true);
        when(permissionRepository.findByUserIdAndWorkspaceId(userId, workspaceId)).thenReturn(List.of(
                AtelierPermissionRule.builder().userId(userId).workspaceId(workspaceId)
                        .tool("bash").commandPrefix("git").effect(PermissionEffect.ALLOW.name()).build()));
        when(runnerToolGateway.bash(eq(runnerTarget), anyString(), eq("git status"), any(), anyLong(), any()))
                .thenReturn(bashOk("sur main\n", 0));
        agentProvider.enqueueToolCall("bash", "command", "git status");
        agentProvider.enqueueFinal("Fait.");

        service.chat(userId, workspaceId, "statut git");

        verify(confirmationGate, never()).await(any(), any(), anyString(), any());
        verify(runnerToolGateway).bash(eq(runnerTarget), anyString(), eq("git status"), any(), anyLong(), any());
    }

    @Test
    void askExtendsToEditsWhenConfigured() {
        runnerWorkspace(false);
        service.setAskBeforeEdit(true); // équivalent acceptEdits : demander avant une édition
        when(runnerToolGateway.readFile(eq(runnerTarget), anyString(), eq("a.ts"))).thenReturn(ok("const a = 1;"));
        when(runnerToolGateway.writeFile(eq(runnerTarget), anyString(), eq("a.ts"), anyString()))
                .thenReturn(ok(""));
        agentProvider.enqueueToolCall("edit_file", "path", "a.ts", "old_string", "1", "new_string", "2");
        agentProvider.enqueueFinal("Modifié.");

        service.chat(userId, workspaceId, "passe a à 2");

        // L'édition est passée par la porte de confirmation (ce qu'elle ne faisait jamais avant F-121).
        verify(confirmationGate).await(eq(userId), eq(workspaceId), anyString(), any());
    }

    @Test
    void editsAreNotConfirmedByDefault() {
        runnerWorkspace(false); // askBeforeEdit reste false (défaut)
        when(runnerToolGateway.readFile(eq(runnerTarget), anyString(), eq("a.ts"))).thenReturn(ok("const a = 1;"));
        when(runnerToolGateway.writeFile(eq(runnerTarget), anyString(), eq("a.ts"), anyString()))
                .thenReturn(ok(""));
        agentProvider.enqueueToolCall("edit_file", "path", "a.ts", "old_string", "1", "new_string", "2");
        agentProvider.enqueueFinal("Modifié.");

        service.chat(userId, workspaceId, "passe a à 2");

        verify(confirmationGate, never()).await(any(), any(), anyString(), any());
    }

    @Test
    void alwaysAllowFromTheConfirmationPersistsARule() {
        runnerWorkspace(true); // ASK par défaut → une invite est posée
        // doAnswer (et non when().thenAnswer) : re-stubber une méthode dont l'answer courant exécute
        // du code déclencherait cet answer avec des arguments nuls pendant l'enregistrement.
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            // L'utilisateur coche « toujours autoriser cette commande ».
            return new RunnerConfirmationGate.Outcome(RunnerConfirmationGate.Decision.ALLOW, null, true);
        }).when(confirmationGate).await(any(), any(), anyString(), any());
        when(permissionRepository.findByUserIdAndWorkspaceIdAndToolAndCommandPrefix(userId, workspaceId, "bash", "npm"))
                .thenReturn(Optional.empty());
        when(runnerToolGateway.bash(eq(runnerTarget), anyString(), eq("npm test"), any(), anyLong(), any()))
                .thenReturn(bashOk("ok\n", 0));
        agentProvider.enqueueToolCall("bash", "command", "npm test");
        agentProvider.enqueueFinal("Fait.");

        service.chat(userId, workspaceId, "lance les tests");

        ArgumentCaptor<AtelierPermissionRule> saved = ArgumentCaptor.forClass(AtelierPermissionRule.class);
        verify(permissionRepository).save(saved.capture());
        assertThat(saved.getValue().getTool()).isEqualTo("bash");
        assertThat(saved.getValue().getCommandPrefix()).isEqualTo("npm");
        assertThat(saved.getValue().effect()).isEqualTo(PermissionEffect.ALLOW);
    }
}
