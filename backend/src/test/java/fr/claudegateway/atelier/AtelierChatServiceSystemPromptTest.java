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
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        // Quota lu pour dériver le plafond de consommation du message (F-39 / SF-39-15).
        org.mockito.Mockito.lenient().when(quotaService.currentUsage(userId)).thenReturn(
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
    void onAWindowsMachineWithoutBashTheRoleSpeaksPowerShell() {
        // F-38 / SF-38-27 : le runner a élu PowerShell faute de bash. Continuer à dicter
        // `ls`/`find`/`grep -n` ferait échouer chaque exploration — et sur cette cible, bash est
        // le seul outil d'exploration déclaré.
        String system = systemPromptOfRunnerProjectDeclaring("powershell");

        assertThat(system).contains("Get-ChildItem");
        assertThat(system).contains("Select-String");
        assertThat(system).doesNotContain("bash (ls, find, grep -n)");
    }

    @Test
    void onAMachineWithOnlyCmdTheRoleSaysThereIsNoGrep() {
        String system = systemPromptOfRunnerProjectDeclaring("cmd");

        assertThat(system).contains("cmd.exe");
        assertThat(system).contains("dir /s /b");
        assertThat(system).contains("findstr");
        assertThat(system).doesNotContain("bash (ls, find, grep -n)");
    }

    @Test
    void anUndeclaredInterpreterKeepsThePosixWording() {
        // Runner antérieur à SF-38-27, ou projet dont aucun runner ne s'est encore connecté :
        // la consigne ne change pas, et elle reste juste sur toute machine Unix.
        String system = systemPromptOfRunnerProjectDeclaring(null);

        assertThat(system).contains("bash (ls, find, grep -n)");
    }

    @Test
    void anUnknownDeclaredInterpreterFallsBackToPosixInsteadOfLeakingIntoThePrompt() {
        // La valeur vient d'un client : hors liste blanche, elle ne doit ni changer la consigne ni
        // y apparaître (décision D6).
        String system = systemPromptOfRunnerProjectDeclaring("<script>fish</script>");

        assertThat(system).contains("bash (ls, find, grep -n)");
        assertThat(system).doesNotContain("fish");
    }

    @Test
    void aHostedProjectIgnoresTheDeclaredInterpreter() {
        // Cible SANDBOX : il n'y a pas de machine, donc pas d'interpréteur. La colonne, même
        // renseignée par un ancien appairage, ne doit rien changer ici.
        Workspace hosted = new Workspace();
        hosted.setId(workspaceId);
        hosted.setUserId(userId);
        hosted.setSource(WorkspaceSource.ARCHIVE);
        hosted.setRunnerShell("cmd");
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(hosted);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(userId, workspaceId, "CLAUDE.md"))
                .thenThrow(new InvalidFilePathException("absent"));

        String system = systemPrompt();

        assertThat(system).contains("list_files, read_file, write_file, search_files");
        assertThat(system).doesNotContain("cmd.exe");
    }

    /** Consigne d'un projet en cible {@code RUNNER} dont le runner a déclaré {@code shell}. */
    private String systemPromptOfRunnerProjectDeclaring(String declaredShell) {
        Workspace runner = new Workspace();
        runner.setId(workspaceId);
        runner.setUserId(userId);
        runner.setSource(WorkspaceSource.ARCHIVE);
        runner.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        runner.setRunnerShell(declaredShell);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runner);
        when(runnerToolGateway.listFiles(any(), any())).thenReturn(runnerOk(""));
        when(runnerToolGateway.readFile(any(), any(), any())).thenReturn(runnerOk("conventions"));
        return systemPrompt();
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
