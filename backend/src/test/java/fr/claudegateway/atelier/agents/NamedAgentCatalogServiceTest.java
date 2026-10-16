package fr.claudegateway.atelier.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.promptsource.PromptSourceStore;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/** F-182 / SF-182-01 — le catalogue d'agents nommés : sujet puis poste, cache d'abord. */
@ExtendWith(MockitoExtension.class)
class NamedAgentCatalogServiceTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private RunnerToolGateway gateway;
    @Mock private PromptSourceStore store;

    private NamedAgentCatalogService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final Map<String, String> listings = new HashMap<>();
    private final Map<String, String> files = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new NamedAgentCatalogService(workspaceService, gateway);
        lenient().when(gateway.listFiles(any(), anyString())).thenAnswer(inv -> {
            RunnerTarget t = inv.getArgument(0);
            String listing = listings.get(t.safeProjectPath());
            return listing == null ? RunnerCallResult.backendError("not_found", "absent") : ok(listing);
        });
        lenient().when(gateway.readFile(any(), anyString(), anyString())).thenAnswer(inv -> {
            RunnerTarget t = inv.getArgument(0);
            String content = files.get(t.safeProjectPath() + "|" + inv.getArgument(2));
            return content == null ? RunnerCallResult.backendError("not_found", "absent") : ok(content);
        });
    }

    private static RunnerCallResult ok(String content) {
        return new RunnerCallResult(true, content, false, null, 5L, null, null, null, "", false);
    }

    private Workspace subject() {
        Workspace w = new Workspace();
        w.setId(UUID.randomUUID());
        w.setUserId(userId);
        w.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        w.setHostId(hostId);
        w.setProjectPath("data-platform");
        return w;
    }

    @Test
    void liveCatalogListsSubjectThenHostAndTheSubjectShadowsTheHost() {
        listings.put("data-platform", "README.md\n.claude/agents/relecteur.md\n.claude/agents/sub/x.md");
        listings.put(".claude/agents", "relecteur.md\ntesteur.md\nnotes.txt");
        files.put("data-platform|.claude/agents/relecteur.md", "---\ndescription: Relecteur du sujet\n---\nRelis.");
        files.put("|.claude/agents/relecteur.md", "---\ndescription: Relecteur du poste\n---\nRelis.");
        files.put("|.claude/agents/testeur.md", "---\noutils: ecriture\n---\nTeste.");

        List<NamedAgent> catalog = service.catalog(userId, subject());

        assertThat(catalog).extracting(NamedAgent::name).containsExactly("relecteur", "testeur");
        assertThat(catalog.get(0).origin()).isEqualTo(NamedAgent.SUJET);
        assertThat(catalog.get(0).description()).isEqualTo("Relecteur du sujet");
        assertThat(catalog.get(1).origin()).isEqualTo(NamedAgent.POSTE);
        assertThat(catalog.get(1).access()).isEqualTo(NamedAgent.ECRITURE);
    }

    @Test
    void aPrimedRunnerIsServedFromTheCacheWithoutAnyRunnerCall() {
        Workspace ws = subject();
        service.setPromptSourceStore(store);
        when(store.isPrimed(userId, ws.getId())).thenReturn(true);
        when(store.tree(userId, ws.getId())).thenReturn(List.of(".claude/agents/relecteur.md"));
        when(store.read(userId, ws.getId(), ".claude/agents/relecteur.md")).thenReturn(Optional.of("Relis."));
        when(store.hostAgentPaths(userId, ws.getId())).thenReturn(Optional.of(List.of(".claude/agents/auditeur-infra.md")));
        when(store.readHostAgent(userId, ws.getId(), ".claude/agents/auditeur-infra.md"))
                .thenReturn(Optional.of("Audite le plan."));

        List<NamedAgent> catalog = service.catalog(userId, ws);

        assertThat(catalog).extracting(NamedAgent::name).containsExactly("relecteur", "auditeur-infra");
        verify(gateway, never()).listFiles(any(), anyString());
        verify(gateway, never()).readFile(any(), anyString(), anyString());
    }

    @Test
    void anEmptyHostIndexMeansNoHostAgent() {
        Workspace ws = subject();
        service.setPromptSourceStore(store);
        when(store.isPrimed(userId, ws.getId())).thenReturn(true);
        when(store.tree(userId, ws.getId())).thenReturn(List.of());
        when(store.hostAgentPaths(userId, ws.getId())).thenReturn(Optional.of(List.of()));

        assertThat(service.catalog(userId, ws)).isEmpty();
        verify(gateway, never()).listFiles(any(), anyString());
    }

    @Test
    void aSilentMachineGivesAnEmptyCatalog() {
        assertThat(service.catalog(userId, subject())).isEmpty();
    }

    @Test
    void theTeamsTerminalHasNoAgent() {
        Workspace ws = subject();
        ws.setTeamsTerminal(true);
        assertThat(service.catalog(userId, ws)).isEmpty();
    }

    @Test
    void loadFindsByNameCaseInsensitively() {
        listings.put("data-platform", ".claude/agents/relecteur.md");
        files.put("data-platform|.claude/agents/relecteur.md", "Relis.");

        assertThat(service.load(userId, subject(), "/Relecteur")).map(NamedAgent::instructions).contains("Relis.");
        assertThat(service.load(userId, subject(), "inconnu")).isEmpty();
    }

    @Test
    void catalogOfIsScopedToTheOwnedWorkspace() {
        Workspace ws = subject();
        when(workspaceService.requireOwned(eq(userId), eq(ws.getId()))).thenReturn(ws);
        listings.put("data-platform", ".claude/agents/relecteur.md");
        files.put("data-platform|.claude/agents/relecteur.md", "---\ndescription: d\n---\nCONSIGNE");

        List<NamedAgentEntry> entries = service.catalogOf(userId, ws.getId());

        assertThat(entries).extracting(NamedAgentEntry::name).containsExactly("relecteur");
        assertThat(entries.toString()).doesNotContain("CONSIGNE");
    }

    @Test
    void nameOfAcceptsOnlyFlatAgentFiles() {
        assertThat(NamedAgentCatalogService.nameOf(".claude/agents/Relecteur.md")).isEqualTo("relecteur");
        assertThat(NamedAgentCatalogService.nameOf("./.claude/agents/testeur.md")).isEqualTo("testeur");
        assertThat(NamedAgentCatalogService.nameOf(".claude/agents/sub/x.md")).isNull();
        assertThat(NamedAgentCatalogService.nameOf(".claude/skills/x.md")).isNull();
        assertThat(NamedAgentCatalogService.nameOf(".claude/agents/notes.txt")).isNull();
    }
}
