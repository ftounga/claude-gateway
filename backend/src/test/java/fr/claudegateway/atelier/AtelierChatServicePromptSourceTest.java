package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.promptsource.PromptSourceCacheProvider;
import fr.claudegateway.atelier.promptsource.PromptSourceFile;
import fr.claudegateway.atelier.promptsource.PromptSourceFileRepository;
import fr.claudegateway.atelier.promptsource.PromptSourceStore;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;

/**
 * Le cache des sources de la consigne branché sur la boucle (F-148 / SF-148-06).
 *
 * <p>Ce qu'on vérifie : une fois le cache amorcé, la consigne d'un projet {@code RUNNER} est servie
 * <b>depuis la base</b> — aucun aller-retour runner d'amorçage — et son contenu est
 * <b>byte-identique</b> à la lecture directe, si bien que le préfixe reste stable (cache F-134
 * préservé). En {@code SANDBOX}, rien n'est mis en cache : les fichiers vivent dans le stockage
 * objet.</p>
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServicePromptSourceTest {

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
    @Mock private PromptSourceFileRepository promptSourceRepo;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private static final String SKILL_BODY = """
            ---
            name: deploy
            description: Déploie le projet sur l'environnement cible.
            ---

            # Déploiement

            Étape 1. SECRET_INTERNE_DU_CORPS
            """;

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

        // Cache réel adossé à un dépôt en mémoire, exécuteur synchrone (le refresh s'applique dans le
        // tour, ce qui rend le test déterministe sans toucher au comportement asynchrone en prod).
        Map<String, PromptSourceFile> rows = new HashMap<>();
        lenient().when(promptSourceRepo.findByUserIdAndWorkspaceIdAndPath(any(), any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get((String) inv.getArgument(2))));
        lenient().when(promptSourceRepo.save(any())).thenAnswer(inv -> {
            PromptSourceFile file = inv.getArgument(0);
            if (file.getId() == null) {
                file.setId(UUID.randomUUID());
            }
            rows.put(file.getPath(), file);
            return file;
        });
        lenient().when(promptSourceRepo.existsByUserIdAndWorkspaceId(any(), any()))
                .thenAnswer(inv -> !rows.isEmpty());
        service.setPromptSource(new PromptSourceCacheProvider(
                new PromptSourceStore(runnerToolGateway, promptSourceRepo), Runnable::run));

        lenient().when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        lenient().when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        lenient().when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private static RunnerCallResult absent() {
        return RunnerCallResult.backendError("not_found", "absent");
    }

    private Workspace runnerWorkspace() {
        Workspace runner = new Workspace();
        runner.setId(workspaceId);
        runner.setUserId(userId);
        runner.setSource(WorkspaceSource.ARCHIVE);
        runner.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        runner.setHostId(hostId);
        return runner;
    }

    private String turn() {
        agentProvider.enqueueFinal("fini");
        service.chat(userId, workspaceId, "bonjour");
        return agentProvider.lastRequest.system();
    }

    @Test
    void primedCacheServesTheConsigneWithoutRunnerRoundTripsAndByteIdentical() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runnerWorkspace());
        lenient().when(runnerHostService.declaredShell(hostId)).thenReturn(null);
        when(runnerToolGateway.listFiles(any(), any()))
                .thenReturn(ok("CLAUDE.md\n.claude/skills/deploy.md"));
        when(runnerToolGateway.readFile(any(), any(), any())).thenAnswer(inv -> {
            String path = inv.getArgument(2);
            return switch (path) {
                case "CLAUDE.md" -> ok("conventions du projet");
                case ".claude/skills/deploy.md" -> ok(SKILL_BODY);
                default -> absent(); // STATE.md / PLAN-ACTION.md
            };
        });

        // Tour 1 : cache vide → lecture directe (runner), puis le refresh synchrone amorce le cache.
        String system1 = turn();
        assertThat(system1).contains("conventions du projet");
        assertThat(system1).contains("- .claude/skills/deploy.md : Déploie le projet sur l'environnement cible.");
        assertThat(system1).doesNotContain("SECRET_INTERNE_DU_CORPS");

        // Tour 2 : le cache est amorcé → plus aucun aller-retour runner d'amorçage.
        clearInvocations(runnerToolGateway);
        String system2 = turn();

        verify(runnerToolGateway, never()).readFile(any(), any(), any());
        verify(runnerToolGateway, never()).listFiles(any(), any());
        // Byte-identique : le préfixe ne bouge pas, le cache de prompt (F-134) tient.
        assertThat(system2).isEqualTo(system1);
    }

    @Test
    void sandboxProjectIsNeverCached() {
        Workspace sandbox = new Workspace();
        sandbox.setId(workspaceId);
        sandbox.setUserId(userId);
        sandbox.setSource(WorkspaceSource.ARCHIVE); // SANDBOX par défaut (pas de cible RUNNER)
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(sandbox);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenReturn("Règle du projet : toujours tester.");

        String system = turn();

        assertThat(system).contains("Règle du projet : toujours tester.");
        // Aucune mise en cache : les fichiers vivent dans le stockage objet, pas d'aller-retour runner.
        verify(promptSourceRepo, never()).save(any());
        verify(runnerToolGateway, never()).readFile(any(), any(), any());
    }
}
