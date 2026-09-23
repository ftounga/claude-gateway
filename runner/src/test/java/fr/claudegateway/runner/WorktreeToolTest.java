package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Cycle de vie des worktrees isolés de {@code task} (F-150 / SF-150-01) : création sous la racine du
 * poste, refus propre sans git, retrait idempotent, reap des orphelins.
 *
 * <p>Ces cas lancent un vrai {@code git} : ils sont ignorés (et non en échec) si le binaire est
 * absent — c'est exactement le comportement de refus propre que la production expose.</p>
 */
class WorktreeToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final GitCli GIT = new GitCli();

    @TempDir
    Path hostRoot;

    private Path project;

    @BeforeEach
    void gitPresent() {
        // Si git est absent, on ne peut rien démontrer d'utile — on saute (le refus propre `not_git`
        // du chemin sans binaire est, lui, couvert par le test dédié qui n'exige pas de dépôt).
        assumeTrue(GIT.run(hostRoot, "--version").present(), "git requis pour ce test");
        project = hostRoot.resolve("mon-projet");
    }

    private void seedGitRepo() throws IOException {
        Files.createDirectories(project);
        assertTrue(GIT.run(project, "init").ok(), "git init");
        GIT.run(project, "config", "user.email", "test@example.com");
        GIT.run(project, "config", "user.name", "Test");
        Files.writeString(project.resolve("fichier.txt"), "contenu suivi");
        assertTrue(GIT.run(project, "add", "-A").ok(), "git add");
        assertTrue(GIT.run(project, "commit", "-m", "initial").ok(), "git commit");
    }

    private WorktreeTool tool() {
        return new WorktreeTool(hostRoot, project, GIT);
    }

    private static ObjectNode task(String taskId) {
        return MAPPER.createObjectNode().put("taskId", taskId);
    }

    @Test
    void creeUnWorktreeSousLaRacineAvecUneBranche() throws IOException {
        seedGitRepo();

        ToolOutcome outcome = tool().execute(WorktreeTool.CREATE, task("t1"), ToolContext.none());

        assertTrue(outcome.ok(), () -> "création : " + outcome.errorMessage());
        JsonNode result = MAPPER.readTree(outcome.content());
        assertEquals(".atelier-worktrees/t1", result.get("worktreePath").asText());
        assertEquals("atelier/task/t1", result.get("branch").asText());

        Path worktree = hostRoot.resolve(".atelier-worktrees").resolve("t1");
        assertTrue(Files.isDirectory(worktree), "le worktree existe sous la racine du poste");
        assertTrue(worktree.startsWith(hostRoot), "jamais hors de la racine du poste");
        assertTrue(Files.exists(worktree.resolve("fichier.txt")), "le contenu suivi est présent");
    }

    @Test
    void refuseProprementSiLeProjetNestPasGit() throws IOException {
        Files.createDirectories(project); // dossier ordinaire, pas de git init

        ToolOutcome outcome = tool().execute(WorktreeTool.CREATE, task("t2"), ToolContext.none());

        assertFalse(outcome.ok());
        assertEquals("not_git", outcome.errorCode());
        assertFalse(Files.exists(hostRoot.resolve(".atelier-worktrees").resolve("t2")),
                "rien n'est écrit quand le projet n'est pas git");
    }

    @Test
    void retraitIdempotent() throws IOException {
        seedGitRepo();
        assertTrue(tool().execute(WorktreeTool.CREATE, task("t3"), ToolContext.none()).ok());
        Path worktree = hostRoot.resolve(".atelier-worktrees").resolve("t3");
        assertTrue(Files.isDirectory(worktree));

        assertTrue(tool().execute(WorktreeTool.REMOVE, task("t3"), ToolContext.none()).ok());
        assertFalse(Files.exists(worktree), "le worktree est démonté");

        // Deuxième retrait : toujours un succès (nettoyage garanti, D9).
        assertTrue(tool().execute(WorktreeTool.REMOVE, task("t3"), ToolContext.none()).ok());
    }

    @Test
    void reapSupprimeLesOrphelinsSaufCeuxAGarder() throws IOException {
        seedGitRepo();
        assertTrue(tool().execute(WorktreeTool.CREATE, task("garde"), ToolContext.none()).ok());
        assertTrue(tool().execute(WorktreeTool.CREATE, task("orphelin"), ToolContext.none()).ok());

        ObjectNode input = MAPPER.createObjectNode();
        input.putArray("keep").add("garde");
        ToolOutcome outcome = tool().execute(WorktreeTool.REAP, input, ToolContext.none());

        assertTrue(outcome.ok());
        assertEquals(1, MAPPER.readTree(outcome.content()).get("reaped").asInt());
        assertTrue(Files.isDirectory(hostRoot.resolve(".atelier-worktrees").resolve("garde")));
        assertFalse(Files.exists(hostRoot.resolve(".atelier-worktrees").resolve("orphelin")));
    }

    @Test
    void taskIdMalformeEstRefuse() throws IOException {
        seedGitRepo();

        ToolOutcome outcome = tool().execute(WorktreeTool.CREATE, task("pas/valide"), ToolContext.none());

        assertFalse(outcome.ok());
        assertEquals("invalid_input", outcome.errorCode());
    }
}
