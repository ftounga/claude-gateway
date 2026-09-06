package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Consigne système de la boucle maison (F-39 / SF-39-02) : les skills y sont <b>annoncés</b>
 * (chemin + description), jamais déversés. Le corps d'un skill se lit à la demande avec
 * {@code read_file} ; c'est ce qui rend le préfixe court et stable, donc réellement cacheable
 * (SF-39-01).
 *
 * <p>La consigne n'étant pas exposée, elle est observée là où elle compte : dans la requête reçue
 * par le fournisseur.</p>
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceSystemPromptTest {

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

    private static final String SKILL_BODY = """
            ---
            name: deploy
            description: Déploie le projet sur l'environnement cible.
            ---

            # Déploiement

            Étape 1 : lancer le pipeline. SECRET_INTERNE_DU_CORPS
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
                new AtelierProperties(null, null, null, null, null, null, null, null, null));

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
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

    /** Issue d'appel runner réussie, forme minimale du contrat §2.4. */
    private static fr.claudegateway.runner.channel.RunnerCallResult runnerOk(String content) {
        return new fr.claudegateway.runner.channel.RunnerCallResult(
                true, content, false, null, 5L, null, null, null, "", false);
    }

    /** Consigne système effectivement envoyée au fournisseur pour un tour trivial. */
    private String systemPrompt() {
        agentProvider.enqueueFinal("fini");
        service.chat(userId, workspaceId, "bonjour");
        return agentProvider.lastRequest.system();
    }

    @Test
    void skillIsAnnouncedByPathAndDescriptionWithoutItsBody() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of(".claude/skills/deploy.md"));
        when(workspaceService.readFile(userId, workspaceId, ".claude/skills/deploy.md")).thenReturn(SKILL_BODY);
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).contains("- .claude/skills/deploy.md : Déploie le projet sur l'environnement cible.");
        assertThat(system).doesNotContain("SECRET_INTERNE_DU_CORPS");
        assertThat(system).contains("read_file");
    }

    @Test
    void onAMachineBackedProjectTheRoleSendsExplorationToBash() {
        // SF-39-05 : annoncer list_files/search_files là où ils ne sont plus déclarés ne produirait
        // que des appels perdus. La consigne suit l'outillage réel.
        Workspace runner = new Workspace();
        runner.setId(workspaceId);
        runner.setUserId(userId);
        runner.setSource(WorkspaceSource.ARCHIVE);
        runner.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runner);
        when(runnerToolGateway.listFiles(any(), any())).thenReturn(runnerOk(""));
        when(runnerToolGateway.readFile(any(), any(), any())).thenReturn(runnerOk("conventions"));

        String system = systemPrompt();

        assertThat(system).contains("bash (ls, find, grep -n)");
        assertThat(system).doesNotContain("search_files");
    }

    @Test
    void projectConventionsAreStillInlinedInFull() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenReturn("Règle du projet : toujours tester.");

        String system = systemPrompt();

        assertThat(system).contains("Conventions du projet (CLAUDE.md)");
        assertThat(system).contains("Règle du projet : toujours tester.");
    }

    @Test
    void noSkillMeansNoCatalogSection() {
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of("src/main.js"));
        when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md")).thenReturn("conventions");

        String system = systemPrompt();

        assertThat(system).doesNotContain("Skills du projet");
    }

    @Test
    void unreadableSkillIsSkippedAndOthersSurvive() {
        when(workspaceService.tree(userId, workspaceId))
                .thenReturn(List.of("skills/broken.md", "skills/ok.md"));
        when(workspaceService.readFile(userId, workspaceId, "skills/broken.md"))
                .thenThrow(new InvalidFilePathException("illisible"));
        when(workspaceService.readFile(userId, workspaceId, "skills/ok.md")).thenReturn("Fait la revue.");
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).doesNotContain("skills/broken.md");
        assertThat(system).contains("- skills/ok.md : Fait la revue.");
    }

    @Test
    void catalogIsBoundedAndAnnouncesTheRemainder() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 55; i++) {
            many.add("skills/s" + i + ".md");
        }
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.copyOf(many));
        lenient().when(workspaceService.readFile(any(), any(), any())).thenAnswer(invocation -> {
            String path = invocation.getArgument(2);
            if ("CLAUDE.md".equals(path)) {
                throw new InvalidFilePathException("absent");
            }
            return "Description de " + path;
        });

        String system = systemPrompt();

        assertThat(system).contains("- skills/s0.md : Description de skills/s0.md");
        assertThat(system).doesNotContain("skills/s50.md");
        assertThat(system).contains("et 5 autre(s) skill(s) non listé(s).");
    }
}
