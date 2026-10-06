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
import fr.claudegateway.runner.channel.RunnerTarget;

/**
 * F-177 / SF-177-01 — la règle écrite s'applique partout : le {@code GOUVERNANCE.md} du poste (racine)
 * puis celui du sujet sont injectés dans la consigne à chaque tour, bornés, servis depuis le cache une
 * fois amorcé, et jamais ceux d'un autre poste.
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceGovernanceFileTest {

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
    private final UUID hostA = UUID.randomUUID();
    private final UUID hostB = UUID.randomUUID();

    /** Fichiers des machines : (poste, chemin de projet) → (chemin → contenu). */
    private final Map<String, Map<String, String>> machines = new HashMap<>();
    private final Map<String, PromptSourceFile> rows = new HashMap<>();

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
        lenient().doAnswer(inv -> rows.remove(((PromptSourceFile) inv.getArgument(0)).getPath()))
                .when(promptSourceRepo).delete(any());
        lenient().when(promptSourceRepo.existsByUserIdAndWorkspaceId(any(), any()))
                .thenAnswer(inv -> !rows.isEmpty());
        service.setPromptSource(new PromptSourceCacheProvider(
                new PromptSourceStore(runnerToolGateway, promptSourceRepo), Runnable::run));

        lenient().when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        lenient().when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        lenient().when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        lenient().when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        lenient().when(runnerToolGateway.listFiles(any(), any())).thenReturn(ok(""));
        lenient().when(runnerToolGateway.readFile(any(), any(), any())).thenAnswer(inv -> {
            RunnerTarget target = inv.getArgument(0);
            String path = inv.getArgument(2);
            Map<String, String> folder = machines.get(target.hostId() + "|" + target.safeProjectPath());
            String content = folder == null ? null : folder.get(path);
            return content == null ? RunnerCallResult.backendError("not_found", "absent") : ok(content);
        });
    }

    private void file(UUID host, String projectPath, String path, String content) {
        machines.computeIfAbsent(host + "|" + projectPath, k -> new HashMap<>()).put(path, content);
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private Workspace subject(UUID host, String projectPath) {
        Workspace w = new Workspace();
        w.setId(workspaceId);
        w.setUserId(userId);
        w.setSource(WorkspaceSource.ARCHIVE);
        w.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        w.setHostId(host);
        w.setProjectPath(projectPath);
        return w;
    }

    private String turn(Workspace workspace) {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        agentProvider.enqueueFinal("fini");
        service.chat(userId, workspaceId, "bonjour");
        return agentProvider.lastRequest.system();
    }

    @Test
    void subjectSeesHostRulesThenSubjectRules() {
        file(hostA, "", "GOUVERNANCE.md", "Commentaires Jira courts.");
        file(hostA, "data-platform", "GOUVERNANCE.md", "Toujours citer le ticket.");

        String system = turn(subject(hostA, "data-platform"));

        assertThat(system).contains(AtelierChatService.HOST_GOVERNANCE_HEADER + "Commentaires Jira courts.");
        assertThat(system).contains(AtelierChatService.SUBJECT_GOVERNANCE_HEADER + "Toujours citer le ticket.");
        assertThat(system.indexOf("Commentaires Jira courts."))
                .isLessThan(system.indexOf("Toujours citer le ticket."));
        assertThat(system).contains(AtelierChatService.HOST_GOVERNANCE_FOOTER)
                .contains(AtelierChatService.SUBJECT_GOVERNANCE_FOOTER);
    }

    @Test
    void anotherSubjectOfTheSameHostSeesTheHostRule() {
        file(hostA, "", "GOUVERNANCE.md", "Commentaires Jira courts.");

        String system = turn(subject(hostA, "lzi"));

        assertThat(system).contains("Commentaires Jira courts.");
        // Sans GOUVERNANCE.md de sujet, pas de section sujet du tout.
        assertThat(system).doesNotContain(AtelierChatService.SUBJECT_GOVERNANCE_HEADER);
    }

    @Test
    void anotherHostNeverSeesTheRule() {
        file(hostA, "", "GOUVERNANCE.md", "Commentaires Jira courts.");

        String system = turn(subject(hostB, "data-platform"));

        assertThat(system).doesNotContain("Commentaires Jira courts.")
                .doesNotContain(AtelierChatService.HOST_GOVERNANCE_HEADER);
    }

    @Test
    void hostTerminalInjectsOnlyItsOwnRootFile() {
        file(hostA, "", "GOUVERNANCE.md", "Commentaires Jira courts.");
        Workspace terminal = subject(hostA, "");
        terminal.setHostTerminal(true);

        String system = turn(terminal);

        assertThat(system).contains(AtelierChatService.HOST_GOVERNANCE_HEADER + "Commentaires Jira courts.");
        assertThat(system).doesNotContain(AtelierChatService.SUBJECT_GOVERNANCE_HEADER);
    }

    @Test
    void longRulesAreBoundedAndTheCutIsSaid() {
        file(hostA, "", "GOUVERNANCE.md", "r".repeat(AtelierChatService.GOVERNANCE_FILE_MAX_CHARS + 500));

        String system = turn(subject(hostA, "data-platform"));

        assertThat(system).contains(AtelierChatService.GOVERNANCE_FILE_TRUNCATION);
        assertThat(system).doesNotContain("r".repeat(AtelierChatService.GOVERNANCE_FILE_MAX_CHARS + 1));
    }

    @Test
    void primedCacheServesBothFilesByteIdenticalWithoutRunnerRoundTrip() {
        file(hostA, "", "GOUVERNANCE.md", "Commentaires Jira courts.");
        file(hostA, "data-platform", "GOUVERNANCE.md", "Toujours citer le ticket.");
        Workspace subject = subject(hostA, "data-platform");

        String first = turn(subject);
        assertThat(rows).containsKey(PromptSourceStore.HOST_GOVERNANCE_PATH)
                .containsKey(PromptSourceStore.GOVERNANCE_FILE);

        clearInvocations(runnerToolGateway);
        String second = turn(subject);

        verify(runnerToolGateway, never()).readFile(any(), any(), any());
        assertThat(second).isEqualTo(first);
    }

    @Test
    void aDeletedHostRuleStopsApplyingAfterRefresh() {
        file(hostA, "", "GOUVERNANCE.md", "Commentaires Jira courts.");
        Workspace subject = subject(hostA, "data-platform");
        turn(subject); // amorce le cache
        machines.get(hostA + "|").remove("GOUVERNANCE.md");
        // Le jeton de rafraîchissement (30 s) est par projet : un autre store simule le tour suivant.
        new PromptSourceStore(runnerToolGateway, promptSourceRepo).refreshHostGovernance(userId, subject);

        assertThat(rows).doesNotContainKey(PromptSourceStore.HOST_GOVERNANCE_PATH);
    }

    @Test
    void sandboxProjectInjectsItsOwnGovernanceFile() {
        Workspace sandbox = new Workspace();
        sandbox.setId(workspaceId);
        sandbox.setUserId(userId);
        sandbox.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        lenient().when(workspaceService.readFile(any(), any(), any())).thenThrow(new RuntimeException("absent"));
        org.mockito.Mockito.doReturn("Règle hébergée.").when(workspaceService)
                .readFile(userId, workspaceId, "GOUVERNANCE.md");

        String system = turn(sandbox);

        assertThat(system).contains(AtelierChatService.SUBJECT_GOVERNANCE_HEADER + "Règle hébergée.")
                .doesNotContain(AtelierChatService.HOST_GOVERNANCE_HEADER);
    }
}
