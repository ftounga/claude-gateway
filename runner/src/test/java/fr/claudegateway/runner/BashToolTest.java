package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Outil {@code bash} du runner (F-38 / SF-38-07) : exécution réelle d'un processus, diffusion de la
 * sortie, code de sortie, confinement du {@code cwd}, bornes et annulation.
 *
 * <p>Les cas qui lancent un vrai processus sont désactivés sous Windows : l'interpréteur y est
 * {@code cmd.exe} et la syntaxe des commandes de test n'y a pas de sens. Les gardes (opt-in,
 * validation d'entrée, confinement) sont vérifiées sur toutes les plateformes.</p>
 */
@DisabledOnOs(OS.WINDOWS)
class BashToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private RecordingContext context;

    @BeforeEach
    void setUp() {
        context = new RecordingContext();
    }

    @Test
    void executeUneCommandeEtRendSonCodeDeSortie() {
        ToolOutcome outcome = enabled().run(command("echo bonjour"), context);

        assertTrue(outcome.ok(), "Une commande qui tourne est un succès d'appel");
        assertEquals(0, outcome.exitCode());
        assertEquals("", outcome.content(), "La sortie passe par le flux, pas par le contenu");
        assertEquals("bonjour\n", context.text("stdout"));
    }

    @Test
    void diffuseStdoutEtStderrSurUnCompteurPartageEtCroissant() {
        ToolOutcome outcome = enabled().run(command("echo un; echo deux 1>&2; echo trois"), context);

        assertTrue(outcome.ok());
        assertEquals("un\ntrois\n", context.text("stdout"));
        assertEquals("deux\n", context.text("stderr"));
        // Le contexte reçoit les fragments dans l'ordre réel d'émission : c'est ce que le compteur
        // seq partagé du dispatcher transporte ensuite (contrat §2.3).
        assertEquals(3, context.chunks.size());
    }

    @Test
    void unCodeDeSortieNonNulResteUnAppelReussi() {
        ToolOutcome outcome = enabled().run(command("exit 3"), context);

        assertTrue(outcome.ok(), "La commande a tourné : son échec est une information, pas une panne");
        assertEquals(3, outcome.exitCode());
    }

    @Test
    void executeDansLeCwdDemande() throws IOException {
        Files.createDirectories(root.resolve("sous/dossier"));
        Files.writeString(root.resolve("sous/dossier/marqueur.txt"), "x");

        ObjectNode input = command("ls");
        input.put("cwd", "sous/dossier");
        ToolOutcome outcome = enabled().run(input, context);

        assertTrue(outcome.ok());
        assertTrue(context.text("stdout").contains("marqueur.txt"));
    }

    @Test
    void refuseUnCwdHorsDeLaRacine() {
        ObjectNode input = command("echo x");
        input.put("cwd", "../ailleurs");

        ToolOutcome outcome = enabled().run(input, context);

        assertFalse(outcome.ok());
        assertEquals("path_outside_root", outcome.errorCode());
        assertTrue(context.chunks.isEmpty(), "Rien n'a été exécuté");
    }

    @Test
    void refuseUnCwdInexistantOuQuiNestPasUnDossier() throws IOException {
        ObjectNode absent = command("echo x");
        absent.put("cwd", "nulle-part");
        assertEquals("not_found", enabled().run(absent, context).errorCode());

        Files.writeString(root.resolve("fichier.txt"), "x");
        ObjectNode fichier = command("echo x");
        fichier.put("cwd", "fichier.txt");
        assertEquals("not_a_file", enabled().run(fichier, context).errorCode());
    }

    @Test
    void refuseLexecutionQuandLaMachineNeLaPasAutorisee() {
        ToolOutcome outcome = new BashTool(new PathGuard(root), false).run(command("echo x"), context);

        assertFalse(outcome.ok());
        assertEquals("unsupported_tool", outcome.errorCode());
        assertTrue(context.chunks.isEmpty(), "Aucun processus n'a été lancé");
    }

    @Test
    void refuseUneCommandeAbsenteVideOuTropLongue() {
        assertEquals("invalid_input", enabled().run(MAPPER.createObjectNode(), context).errorCode());
        assertEquals("invalid_input", enabled().run(command("   "), context).errorCode());
        assertEquals("invalid_input",
                enabled().run(command("x".repeat(BashTool.MAX_COMMAND_CHARS + 1)), context).errorCode());
    }

    @Test
    void tueLeProcessusQuandLeThreadEstInterrompu() throws Exception {
        BashTool tool = enabled();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<ToolOutcome> result = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            started.countDown();
            result.set(tool.run(command("sleep 30"), context));
        });
        worker.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread.sleep(300); // laisse le processus démarrer
        worker.interrupt();
        worker.join(10_000);

        assertFalse(worker.isAlive(), "L'appel rend la main dès l'interruption : le processus est tué");
        assertNotNull(result.get());
        assertFalse(result.get().ok());
        assertEquals("cancelled", result.get().errorCode());
    }

    @Test
    void coupeUneSortieVolumineuseEtMarqueLaTroncature() {
        // ~1 Mio de sortie : bien au-delà du plafond de diffusion de 256 Kio.
        ToolOutcome outcome = enabled().run(
                command("head -c 1048576 /dev/zero | tr '\\0' 'a'"), context);

        assertTrue(outcome.ok());
        assertTrue(outcome.truncated(), "Le producteur signale qu'il a coupé");
        assertTrue(context.bytes() <= BashTool.MAX_STREAM_BYTES,
                "La sortie diffusée reste sous le plafond : " + context.bytes());
    }

    @Test
    void refuseUneSecondeCommandeSimultanee() throws Exception {
        BashTool tool = enabled();
        CountDownLatch started = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            started.countDown();
            tool.run(command("sleep 2"), context);
        });
        first.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);

        ToolOutcome refused = tool.run(command("echo x"), new RecordingContext());

        assertFalse(refused.ok());
        assertEquals("denied", refused.errorCode());
        first.interrupt();
        first.join(10_000);
    }

    @Test
    void neBloquePasSurUneCommandeQuiLitLentreeStandard() {
        // stdin est fermé au démarrage : `cat` reçoit EOF au lieu d'attendre indéfiniment.
        ToolOutcome outcome = enabled().run(command("cat"), context);

        assertTrue(outcome.ok());
        assertEquals(0, outcome.exitCode());
    }

    @Test
    void unOutilFichierNeRenvoieAucunCodeDeSortie() throws IOException {
        Files.writeString(root.resolve("a.txt"), "x");
        ToolOutcome outcome = new FileTools(new PathGuard(root)).execute("read_file",
                MAPPER.createObjectNode().put("path", "a.txt"), ToolContext.none());

        assertNull(outcome.exitCode(), "exitCode n'existe que pour bash (contrat §2.4)");
    }

    private BashTool enabled() {
        return new BashTool(new PathGuard(root), true);
    }

    private static ObjectNode command(String command) {
        return MAPPER.createObjectNode().put("command", command);
    }

    /** Contexte de test : mémorise les fragments diffusés, dans l'ordre. */
    private static final class RecordingContext implements ToolContext {
        private final List<String[]> chunks = new ArrayList<>();

        @Override
        public synchronized void stream(String stream, String chunk) {
            chunks.add(new String[] { stream, chunk });
        }

        @Override
        public long timeoutMs() {
            return 30_000L;
        }

        @Override
        public boolean cancelled() {
            return false;
        }

        synchronized String text(String stream) {
            StringBuilder builder = new StringBuilder();
            chunks.stream().filter(c -> c[0].equals(stream)).forEach(c -> builder.append(c[1]));
            return builder.toString();
        }

        synchronized long bytes() {
            return chunks.stream().mapToLong(c -> c[1].getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
        }
    }
}
