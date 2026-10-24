package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Aiguillage des outils du runner (F-38 / SF-38-07) et capacités annoncées. */
class ToolRouterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    @Test
    void annonceBashUniquementQuandLaMachineLaAutorise() {
        assertEquals(List.of("files"), router(false).capabilities());
        assertEquals(List.of("files", "bash", "bash_parallel_4", "proof"), router(true).capabilities());
    }

    @Test
    void annonceBashBackgroundQuandUnRegistreEstMonte() {
        // F-121 / SF-121-07 : la capacité n'apparaît que si bash est autorisé ET qu'un registre existe.
        PathResolver guard = new PathResolver(root);
        ToolRouter withBackground = new ToolRouter(new FileTools(guard),
                new BashTool(guard, true, ShellElection.elect(), new BackgroundShells()));
        assertEquals(List.of("files", "bash", "bash_parallel_4", "proof", "bash_background"), withBackground.capabilities());
        // Sans registre : pas d'arrière-plan annoncé (retro-compat).
        assertEquals(List.of("files", "bash", "bash_parallel_4", "proof"), router(true).capabilities());
    }

    @Test
    void aiguilleLesOutilsFichiersVersFileTools() throws IOException {
        Files.writeString(root.resolve("a.txt"), "bonjour");

        ToolOutcome outcome = router(true).execute("read_file",
                MAPPER.createObjectNode().put("path", "a.txt"), ToolContext.none());

        assertTrue(outcome.ok());
        assertEquals("bonjour", outcome.content());
    }

    @Test
    void aiguilleBashVersLeBashTool() {
        // Routeur bash désactivé : le refus prouve que l'appel est bien parti au BashTool, et pas
        // au FileTools (qui répondrait « unsupported_tool » avec un autre message).
        ToolOutcome outcome = router(false).execute("bash",
                MAPPER.createObjectNode().put("command", "echo x"), ToolContext.none());

        assertFalse(outcome.ok());
        assertEquals("unsupported_tool", outcome.errorCode());
        assertTrue(outcome.errorMessage().contains("pas activée"));
    }

    @Test
    void unOutilInconnuResteRefuseParLesOutilsFichiers() {
        ToolOutcome outcome = router(true).execute("deploy", MAPPER.createObjectNode(),
                ToolContext.none());

        assertFalse(outcome.ok());
        assertEquals("unsupported_tool", outcome.errorCode());
    }

    @Test
    void aiguilleLesOperationsWorktreeVersLeWorktreeTool() {
        // Un taskId malformé fait répondre `invalid_input` au WorktreeTool : ce code prouve que
        // l'appel a bien été routé là, et non au FileTools (qui répondrait `unsupported_tool`).
        ToolOutcome outcome = routerWithWorktree().execute("worktree_create",
                MAPPER.createObjectNode().put("taskId", "pas/valide"), ToolContext.none());

        assertFalse(outcome.ok());
        assertEquals("invalid_input", outcome.errorCode());
    }

    @Test
    void sansWorktreeToolUneOperationWorktreeEstNonSupportee() {
        // Rétro-compatibilité : un runner sans ce montage (constructeur historique) route
        // `worktree_*` vers FileTools, qui répond `unsupported_tool` — exactement un runner antérieur.
        ToolOutcome outcome = router(true).execute("worktree_create",
                MAPPER.createObjectNode().put("taskId", "abc"), ToolContext.none());

        assertFalse(outcome.ok());
        assertEquals("unsupported_tool", outcome.errorCode());
    }

    @Test
    void annonceLaCapaciteVideoSansTeamsEtAiguilleSesOutils() throws IOException {
        // F-190 / SF-190-01 : les outils vidéo sont montés, Teams ou pas.
        PathResolver guard = new PathResolver(root);
        fr.claudegateway.runner.video.VideoTools video = new fr.claudegateway.runner.video.VideoTools(
                new fr.claudegateway.runner.video.VideoFramesWorker(
                        fr.claudegateway.runner.teams.VideoFixtures.frames(root,
                                (command, dir, timeout) -> new fr.claudegateway.runner.teams
                                        .ProcessRunner.ProcessResult(0, List.of(), List.of(), false))));
        ToolRouter router = new ToolRouter(new FileTools(guard),
                new BashTool(guard, false, ShellElection.elect()), null, null, video.bind(guard));

        assertEquals(List.of("files", "video"), router.capabilities());
        ToolOutcome outcome = router.execute("video_images_status",
                MAPPER.createObjectNode().put("job_id", "inconnu"), ToolContext.none());
        assertFalse(outcome.ok());
        assertTrue(outcome.errorMessage().contains("Relance video_images"), outcome.errorMessage());
    }

    @Test
    void refuseLesOutilsVideoQuandIlsNeSontPasMontes() {
        ToolOutcome outcome = router(false).execute("video_images",
                MAPPER.createObjectNode().put("path", "demo.mp4"), ToolContext.none());
        assertFalse(outcome.ok());
        assertTrue(outcome.errorMessage().contains("ne sait pas extraire"), outcome.errorMessage());
    }

    @Test
    void leRenduVideoNEstAnnonceQueSiLesCommandesSontAutorisees() {
        // F-215 / SF-215-02 : le rendu lance des programmes — il suit l'autorisation de bash.
        PathResolver guard = new PathResolver(root);
        fr.claudegateway.runner.video.render.RenderTools render = new fr.claudegateway.runner.video.render.RenderTools(
                null, null);
        ToolRouter allowed = new ToolRouter(new FileTools(guard), new BashTool(guard, true, ShellElection.elect()),
                null, null, null, render.bind(guard));
        assertTrue(allowed.capabilities().contains("video_render"), allowed.capabilities().toString());
        ToolOutcome unknown = allowed.execute("video_render_status",
                MAPPER.createObjectNode().put("job_id", "0123456789ab"), ToolContext.none());
        assertTrue(unknown.errorMessage().contains("Relance video_render"), unknown.errorMessage());

        ToolRouter forbidden = new ToolRouter(new FileTools(guard), new BashTool(guard, false, ShellElection.elect()),
                null, null, null, render.bind(guard));
        assertFalse(forbidden.capabilities().contains("video_render"));
        ToolOutcome refused = forbidden.execute("video_render",
                MAPPER.createObjectNode().put("dossier", "x"), ToolContext.none());
        assertFalse(refused.ok());
        assertTrue(refused.errorMessage().contains("désactivée"), refused.errorMessage());

        ToolOutcome old = router(true).execute("video_render", MAPPER.createObjectNode(), ToolContext.none());
        assertTrue(old.errorMessage().contains("mettez-le à jour"), old.errorMessage());
    }

    private ToolRouter router(boolean allowBash) {
        PathResolver guard = new PathResolver(root);
        return new ToolRouter(new FileTools(guard), new BashTool(guard, allowBash, ShellElection.elect()));
    }

    private ToolRouter routerWithWorktree() {
        PathResolver guard = new PathResolver(root);
        return new ToolRouter(new FileTools(guard), new BashTool(guard, true, ShellElection.elect()),
                null, new WorktreeTool(root, root, new GitCli()));
    }
}
