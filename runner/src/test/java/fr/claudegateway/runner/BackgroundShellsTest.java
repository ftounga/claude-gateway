package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Commandes en arrière-plan (F-121 / SF-121-07) : lancement détaché, relecture par curseur, arrêt,
 * plafond du registre et retro-compat (arrière-plan absent = synchrone).
 *
 * <p>Les cas qui lancent un vrai processus sont désactivés sous Windows, comme {@link BashToolTest}.</p>
 */
@DisabledOnOs(OS.WINDOWS)
class BackgroundShellsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ShellElection POSIX_SHELL =
            ShellElection.elect(OperatingSystem.LINUX, System.getenv(), Files::isExecutable);

    @TempDir
    Path root;

    @Test
    void lanceEnArrierePlanEtRendUnIdentifiantSansAttendre() throws Exception {
        BashTool tool = withBackground();

        ToolOutcome outcome = tool.run(background("echo bonjour"), ToolContext.none());

        assertTrue(outcome.ok(), outcome.errorMessage());
        assertTrue(outcome.content().contains("bash_1"), outcome.content());
        // Le processus a rendu la main tout de suite ; on relit sa sortie après coup.
        Thread.sleep(500);
        ToolOutcome read = tool.outputOf(bashOutput("bash_1"));
        assertTrue(read.ok());
        assertTrue(read.content().contains("bonjour"), read.content());
        assertTrue(read.content().contains("terminé"), read.content());
    }

    @Test
    void bashOutputNeRendQueLaSortieNouvelleDepuisLaDerniereLecture() throws Exception {
        BashTool tool = withBackground();
        tool.run(background("echo un; sleep 1; echo deux"), ToolContext.none());
        Thread.sleep(300);

        String first = tool.outputOf(bashOutput("bash_1")).content();
        assertTrue(first.contains("un"), first);
        assertFalse(first.contains("deux"), first);

        Thread.sleep(1200);
        String second = tool.outputOf(bashOutput("bash_1")).content();
        assertTrue(second.contains("deux"), second);
        assertFalse(second.contains("un"), "La sortie déjà lue n'est pas rejouée : " + second);
    }

    @Test
    void killShellArreteLeProcessusEtEstIdempotent() throws Exception {
        BashTool tool = withBackground();
        tool.run(background("sleep 30"), ToolContext.none());
        Thread.sleep(300);

        ToolOutcome killed = tool.killOf(killShell("bash_1"));
        assertTrue(killed.ok());
        assertTrue(killed.content().contains("arrêtée"), killed.content());

        // Second appel : idempotent, ne lève pas.
        assertTrue(tool.killOf(killShell("bash_1")).ok());
    }

    @Test
    void identifiantInconnuRendNotFound() {
        BashTool tool = withBackground();
        assertEquals("not_found", tool.outputOf(bashOutput("bash_404")).errorCode());
        assertEquals("not_found", tool.killOf(killShell("bash_404")).errorCode());
    }

    @Test
    void refuseAuDelaDuPlafondDeCommandesDeFond() {
        BackgroundShells registry = new BackgroundShells();
        BashTool tool = new BashTool(new PathResolver(root), true, POSIX_SHELL, registry);
        for (int i = 0; i < BackgroundShells.MAX_SHELLS; i++) {
            assertTrue(tool.run(background("sleep 30"), ToolContext.none()).ok());
        }

        ToolOutcome refused = tool.run(background("sleep 30"), ToolContext.none());

        assertFalse(refused.ok());
        assertEquals("denied", refused.errorCode());
    }

    @Test
    void unBashDeFondNeBloquePasUnBashDePremierPlan() throws Exception {
        BashTool tool = withBackground();
        tool.run(background("sleep 30"), ToolContext.none());
        Thread.sleep(200);

        // Le sémaphore « une commande à la fois » n'est pas pris par le fond : le premier plan passe.
        ToolOutcome foreground = tool.run(command("echo direct"), new NoopContext());

        assertTrue(foreground.ok(), foreground.errorMessage());
        assertEquals(0, foreground.exitCode());
    }

    @Test
    void sansRegistreRunInBackgroundRetombeEnSynchrone() {
        // Retro-compat : un montage sans arrière-plan ignore le drapeau et exécute en synchrone.
        BashTool tool = new BashTool(new PathResolver(root), true, POSIX_SHELL);

        NoopContext context = new NoopContext();
        ToolOutcome outcome = tool.run(background("exit 0"), context);

        assertTrue(outcome.ok());
        assertEquals(0, outcome.exitCode(), "Exécution synchrone : un code de sortie, pas un identifiant");
    }

    @Test
    void bashOutputSansArrierePlanEstUnsupported() {
        BashTool tool = new BashTool(new PathResolver(root), true, POSIX_SHELL);
        assertEquals("unsupported_tool", tool.outputOf(bashOutput("bash_1")).errorCode());
        assertEquals("unsupported_tool", tool.killOf(killShell("bash_1")).errorCode());
    }

    private BashTool withBackground() {
        return new BashTool(new PathResolver(root), true, POSIX_SHELL, new BackgroundShells());
    }

    private static ObjectNode command(String command) {
        return MAPPER.createObjectNode().put("command", command);
    }

    private static ObjectNode background(String command) {
        return command(command).put("background", true);
    }

    private static ObjectNode bashOutput(String id) {
        return MAPPER.createObjectNode().put("bash_id", id);
    }

    private static ObjectNode killShell(String id) {
        return MAPPER.createObjectNode().put("shell_id", id);
    }

    /** Contexte neutre : rien à diffuser, jamais annulé. */
    private static final class NoopContext implements ToolContext {
        @Override
        public void stream(String stream, String chunk) {
        }

        @Override
        public long timeoutMs() {
            return 30_000L;
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    }
}
