package fr.claudegateway.atelier.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/** F-177 / SF-177-03 — le catalogue de skills : sujet puis poste, invocation déterministe. */
@ExtendWith(MockitoExtension.class)
class SkillCatalogServiceTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private RunnerToolGateway gateway;

    private SkillCatalogService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    /** dossier de projet → listage ; dossier|chemin → contenu. */
    private final Map<String, String> listings = new HashMap<>();
    private final Map<String, String> files = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new SkillCatalogService(workspaceService, gateway);
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
    void catalogListsSubjectThenHostSkillsAndTheSubjectShadowsTheHost() {
        listings.put("data-platform", "README.md\n.claude/skills/deploy.md\n.claude/skills/ticket-jira.md");
        listings.put(".claude/skills", "ticket-jira.md\nexplique.md\nnotes.txt");
        files.put("data-platform|.claude/skills/deploy.md", "---\ndescription: Déploie le projet.\n---\n# x");
        files.put("data-platform|.claude/skills/ticket-jira.md", "# Ticket\nVersion du sujet.");
        files.put("|.claude/skills/explique.md", "# Explique\nExplique un bout de code.");

        var catalog = service.catalog(userId, subject());

        assertThat(catalog).extracting(SkillEntry::name).containsExactly("deploy", "ticket-jira", "explique");
        assertThat(catalog).extracting(SkillEntry::origin).containsExactly("SUJET", "SUJET", "POSTE");
        assertThat(catalog.get(0).description()).isEqualTo("Déploie le projet.");
        assertThat(catalog.get(1).description()).isEqualTo("Version du sujet.");
        assertThat(catalog.get(2).path()).isEqualTo(".claude/skills/explique.md");
    }

    @Test
    void loadReadsTheHostSkillAtTheRoot() {
        listings.put("data-platform", "");
        listings.put(".claude/skills", "ticket-jira/SKILL.md");
        files.put("|.claude/skills/ticket-jira/SKILL.md", "Étapes du ticket.");

        var loaded = service.load(userId, subject(), "Ticket-Jira");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().content()).isEqualTo("Étapes du ticket.");
        assertThat(loaded.get().entry().origin()).isEqualTo("POSTE");
    }

    @Test
    void unknownSkillLoadsNothing() {
        listings.put("data-platform", ".claude/skills/deploy.md");
        assertThat(service.load(userId, subject(), "inconnu")).isEmpty();
    }

    @Test
    void hostTerminalListsOnlyTheHostSkillsWithoutTheRecursiveRootTree() {
        Workspace terminal = subject();
        terminal.setHostTerminal(true);
        terminal.setProjectPath("");
        listings.put(".claude/skills", "explique.md");

        var catalog = service.catalog(userId, terminal);

        assertThat(catalog).extracting(SkillEntry::name).containsExactly("explique");
        verify(gateway, never()).listFiles(org.mockito.ArgumentMatchers.argThat(
                (RunnerTarget t) -> t != null && t.safeProjectPath().isEmpty()), anyString());
    }

    @Test
    void invocationParsing() {
        assertThat(SkillCatalogService.invocationOf("/ticket-jira DECPB-200")).get()
                .satisfies(i -> {
                    assertThat(i.name()).isEqualTo("ticket-jira");
                    assertThat(i.rest()).isEqualTo("DECPB-200");
                });
        assertThat(SkillCatalogService.invocationOf("/Explique")).get()
                .extracting(SkillCatalogService.Invocation::name).isEqualTo("explique");
        assertThat(SkillCatalogService.invocationOf("bonjour /ticket")).isEmpty();
        assertThat(SkillCatalogService.invocationOf("/chemin/vers/fichier")).isEmpty();
    }

    @Test
    void namesAndDescriptions() {
        assertThat(SkillCatalogService.nameOf(".claude/skills/a.md")).isEqualTo("a");
        assertThat(SkillCatalogService.nameOf("skills/b.md")).isEqualTo("b");
        assertThat(SkillCatalogService.nameOf(".claude/skills/c/SKILL.md")).isEqualTo("c");
        assertThat(SkillCatalogService.nameOf(".claude/skills/c/notes.md")).isNull();
        assertThat(SkillCatalogService.nameOf("src/a.md")).isNull();
        assertThat(SkillCatalogService.describe("---\nname: x\n---\n# T\n\nPremière ligne.")).isEqualTo("Première ligne.");
        assertThat(SkillCatalogService.describe(null)).isEmpty();
    }
}
