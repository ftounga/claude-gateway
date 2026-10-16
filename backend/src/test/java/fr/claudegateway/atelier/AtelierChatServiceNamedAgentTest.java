package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
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

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.agents.NamedAgent;
import fr.claudegateway.atelier.agents.NamedAgentCatalogService;
import fr.claudegateway.atelier.agents.NamedAgentParser;
import fr.claudegateway.atelier.agents.NamedAgentPrompts;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * F-182 / SF-182-02 — déléguer à un agent nommé : {@code explore}/{@code task} avec {@code agent}, l'annonce
 * dans le message, {@code /agent <nom>}. La consigne, les outils et le modèle de l'agent remplacent ceux par
 * défaut ; les contraintes d'exécution (lecture seule, worktree) restent celles de la passerelle.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceNamedAgentTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    @Mock private NamedAgentCatalogService agents;

    private RecordingProvider provider;
    private AtelierChatService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private Workspace workspace;

    private static final String WORKTREE_JSON =
            "{\"worktreePath\":\".atelier-worktrees/wt1\",\"branch\":\"atelier/task/wt1\"}";

    /** Enregistre CHAQUE requête (consigne système, outils, modèle) — boucle principale et sous-boucles. */
    static final class RecordingProvider extends StubAiAgentProvider {
        final List<AgentTurnRequest> requests = Collections.synchronizedList(new ArrayList<>());

        @Override
        public AgentTurn nextTurn(AgentTurnRequest request) {
            requests.add(request);
            return super.nextTurn(request);
        }

        AgentTurnRequest subLoop() {
            return requests.stream().filter(r -> r.system() != null && r.system().startsWith("Tu es l'agent nommé"))
                    .findFirst().orElseThrow();
        }

        boolean anySubLoop() {
            return requests.stream().anyMatch(r -> r.system() != null && r.system().startsWith("Tu es l'agent nommé"));
        }
    }

    @BeforeEach
    void setUp() {
        provider = new RecordingProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) provider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true, null, null, null, null, null, null, null, null, null, null,
                        "claude-sonnet-5"));
        service.setNamedAgents(agents);
        when(confirmationGate.await(any(), any(), anyString(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return new fr.claudegateway.runner.exec.RunnerConfirmationGate.Outcome(
                    fr.claudegateway.runner.exec.RunnerConfirmationGate.Decision.ALLOW, null);
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
        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
        when(agents.catalog(any(), any())).thenReturn(List.of());
    }

    private void runnerWorkspace() {
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private static NamedAgent agent(String name, String content) {
        return NamedAgentParser.parse(name, ".claude/agents/" + name + ".md", NamedAgent.POSTE, content);
    }

    private void declare(NamedAgent... declared) {
        when(agents.catalog(any(), any())).thenReturn(List.of(declared));
    }

    private AgentContentBlock.ToolResult lastToolResult() {
        AgentContentBlock.ToolResult found = null;
        for (AgentMessage message : provider.lastRequest.messages()) {
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.ToolResult result) {
                    found = result;
                }
            }
        }
        assertThat(found).isNotNull();
        return found;
    }

    @Test
    void theCatalogIsAnnouncedInTheMessageNeverInTheSystem() {
        declare(agent("relecteur", "---\ndescription: Relit un diff.\n---\nRelis."),
                agent("vide", "---\ndescription: cassé\n---\n"));
        provider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "bonjour");

        AgentTurnRequest main = provider.requests.get(0);
        assertThat(String.valueOf(main.messages())).contains(NamedAgentPrompts.CATALOG_HEADER.strip())
                .contains("- relecteur (poste, lecture → explore) : Relit un diff.")
                .doesNotContain("- vide");
        assertThat(main.system()).doesNotContain("relecteur").doesNotContain("Agents nommés");
    }

    @Test
    void withoutAgentsTheMessageIsUnchanged() {
        provider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "bonjour");
        assertThat(String.valueOf(provider.requests.get(0).messages())).doesNotContain("Agents nommés");
    }

    @Test
    void exploreAndTaskDeclareTheAgentParameterWithAStableDescription() {
        runnerWorkspace();
        provider.enqueueFinal("ok");
        service.chat(userId, workspaceId, "bonjour");
        List<AgentTool> tools = provider.requests.get(0).tools();
        AgentTool explore = tools.stream().filter(t -> t.name().equals("explore")).findFirst().orElseThrow();
        AgentTool task = tools.stream().filter(t -> t.name().equals("task")).findFirst().orElseThrow();
        assertThat(String.valueOf(explore.inputSchema())).contains("agent");
        assertThat(String.valueOf(task.inputSchema())).contains("agent");
        assertThat(explore.description()).contains(AtelierChatService.NAMED_AGENT_PARAM_NOTE);
    }

    @Test
    void exploreWithAnAgentUsesItsInstructionsReadToolsAndTheMainModel() {
        declare(agent("relecteur", "---\noutils: read_file, grep, bash\n---\nCONSIGNE_DU_RELECTEUR"));
        provider.enqueueToolCall("explore", "question", "relis le module", "agent", "relecteur");
        provider.enqueueFinal("Constat : rien à signaler.");
        provider.enqueueFinal("Terminé.");

        service.chat(userId, workspaceId, "relis");

        AgentTurnRequest sub = provider.subLoop();
        assertThat(sub.system()).contains("CONSIGNE_DU_RELECTEUR").contains("Tu ne peux que LIRE");
        // bash est retiré : explore reste en lecture seule, quoi que dise le fichier.
        assertThat(sub.tools()).extracting(AgentTool::name).containsExactly("read_file", "grep");
        // D3 : le modèle de la Forge, pas le modèle d'exploration (Sonnet).
        assertThat(sub.model()).isEqualTo(AtelierProperties.DEFAULT_MODEL);
        assertThat(lastToolResult().content()).contains("Constat : rien à signaler.");
    }

    @Test
    void aJustifiedModelIsUsedByTheAgent() {
        declare(agent("relecteur", "---\nmodele: claude-sonnet-5\njustification: lecture courte\n---\nRelis."));
        provider.enqueueToolCall("explore", "question", "relis", "agent", "relecteur");
        provider.enqueueFinal("ok");
        provider.enqueueFinal("Terminé.");

        service.chat(userId, workspaceId, "relis");

        assertThat(provider.subLoop().model()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void anUnknownAgentIsAnErrorAndNothingRuns() {
        declare(agent("relecteur", "Relis."));
        provider.enqueueToolCall("explore", "question", "relis", "agent", "inconnu");
        provider.enqueueFinal("Terminé.");

        service.chat(userId, workspaceId, "relis");

        AgentContentBlock.ToolResult result = lastToolResult();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("introuvable").contains("relecteur");
        assertThat(provider.anySubLoop()).isFalse();
    }

    @Test
    void anUnusableAgentIsAnError() {
        declare(agent("vide", "---\ndescription: x\n---\n"));
        provider.enqueueToolCall("explore", "question", "relis", "agent", "vide");
        provider.enqueueFinal("Terminé.");

        service.chat(userId, workspaceId, "relis");

        assertThat(lastToolResult().isError()).isTrue();
        assertThat(lastToolResult().content()).contains("inutilisable").contains("Consigne vide");
    }

    @Test
    void taskWithAWritingAgentKeepsTheWorktreeAndFiltersTheTools() {
        runnerWorkspace();
        declare(agent("testeur", "---\noutils: read_file, write_file, bash\n---\nCONSIGNE_DU_TESTEUR"));
        when(runnerToolGateway.worktreeCreate(any(), anyString(), anyString())).thenReturn(ok(WORKTREE_JSON));
        when(runnerToolGateway.worktreeRemove(any(), anyString(), anyString())).thenReturn(ok(""));
        provider.enqueueToolCall("task", "prompt", "écris les tests", "agent", "testeur");
        provider.enqueueToolCall("edit_file", "path", "a.txt", "old", "x", "new", "y"); // hors de sa liste
        provider.enqueueFinal("Tests écrits.");
        provider.enqueueFinal("Terminé.");

        service.chat(userId, workspaceId, "teste");

        AgentTurnRequest sub = provider.subLoop();
        assertThat(sub.system()).contains("CONSIGNE_DU_TESTEUR").contains("worktree git ISOLÉ");
        assertThat(sub.tools()).extracting(AgentTool::name).containsExactlyInAnyOrder("read_file", "write_file", "bash");
        assertThat(sub.model()).isEqualTo(AtelierProperties.DEFAULT_MODEL);
        verify(runnerToolGateway).worktreeCreate(any(), anyString(), anyString());
        verify(runnerToolGateway).worktreeRemove(any(), anyString(), anyString());
        // Double verrou : edit_file, absent de la liste de l'agent, n'a jamais atteint le poste.
        verify(runnerToolGateway, never()).writeFile(any(), anyString(), anyString(), anyString());
        verify(runnerToolGateway, never()).readFile(any(), anyString(), eq("a.txt"));
        assertThat(provider.requests.stream().filter(r -> r.system() != null
                && r.system().startsWith("Tu es l'agent nommé")).map(r -> String.valueOf(r.messages())))
                .anyMatch(m -> m.contains("n'est pas autorisé à l'agent"));
    }

    @Test
    void anUnknownAgentInTaskCreatesNoWorktree() {
        runnerWorkspace();
        provider.enqueueToolCall("task", "prompt", "écris", "agent", "fantome");
        provider.enqueueFinal("Terminé.");

        service.chat(userId, workspaceId, "écris");

        assertThat(lastToolResult().isError()).isTrue();
        verify(runnerToolGateway, never()).worktreeCreate(any(), anyString(), anyString());
    }

    @Test
    void withoutAnAgentExploreIsUnchanged() {
        provider.enqueueToolCall("explore", "question", "où est X ?");
        provider.enqueueFinal("X est là.");
        provider.enqueueFinal("Terminé.");

        service.chat(userId, workspaceId, "cherche X");

        assertThat(provider.anySubLoop()).isFalse();
        assertThat(provider.requests.stream().anyMatch(r -> r.system() != null && r.system().startsWith("Tu explores")))
                .isTrue();
        assertThat(provider.requests.stream().filter(r -> r.system() != null && r.system().startsWith("Tu explores"))
                .findFirst().orElseThrow().model()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void slashAgentJoinsTheInvocationToTheMessage() {
        declare(agent("relecteur", "Relis."));
        provider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "/agent relecteur relis la MR 42");

        String messages = String.valueOf(provider.requests.get(0).messages());
        assertThat(messages).contains(NamedAgentPrompts.INVOKED_HEADER + "relecteur")
                .contains("appelle `explore` avec agent: \"relecteur\"")
                .contains("/agent relecteur relis la MR 42");
        assertThat(provider.requests.get(0).system()).doesNotContain(NamedAgentPrompts.INVOKED_HEADER);
        verify(messageRepository).save(org.mockito.ArgumentMatchers.argThat(
                (AtelierMessage m) -> "USER".equals(m.getRole()) && "/agent relecteur relis la MR 42".equals(m.getContent())));
    }

    @Test
    void slashAgentForAWriterOnAPosteGoesThroughTask() {
        runnerWorkspace();
        declare(agent("testeur", "---\noutils: ecriture\n---\nTeste."));
        provider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "/agent testeur ajoute un test");

        assertThat(String.valueOf(provider.requests.get(0).messages()))
                .contains("appelle `task` avec agent: \"testeur\"");
    }

    @Test
    void slashAgentUnknownSaysWhichExist() {
        declare(agent("relecteur", "Relis."));
        provider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "/agent fantome fais");

        assertThat(String.valueOf(provider.requests.get(0).messages()))
                .contains("Aucun agent nommé « fantome »").contains("Agents disponibles : relecteur");
    }

    @Test
    void slashAgentIsNeverLoadedAsASkill() {
        fr.claudegateway.atelier.skills.SkillCatalogService skills =
                org.mockito.Mockito.mock(fr.claudegateway.atelier.skills.SkillCatalogService.class);
        service.setSkillCatalog(skills);
        declare(agent("relecteur", "Relis."));
        provider.enqueueFinal("ok");

        service.chat(userId, workspaceId, "/agent relecteur relis");

        verify(skills, never()).load(any(), any(), eq("agent"));
    }
}
