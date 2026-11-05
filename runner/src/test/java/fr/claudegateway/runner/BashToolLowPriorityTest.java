package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * F-247 / SF-247-06 — le runner cède la place : une commande de basse priorité est refusée quand le poste travaille
 * ({@code busy_deferred}), tuée en ≤ 500 ms quand une commande du PO arrive ({@code preempted}), lancée en priorité
 * basse, bornée, et son CPU est remonté. La capacité {@code low_priority} est annoncée.
 */
@DisabledOnOs(OS.WINDOWS)
class BashToolLowPriorityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ShellElection POSIX_SHELL =
            ShellElection.elect(OperatingSystem.LINUX, System.getenv(), Files::isExecutable);

    @TempDir
    Path root;

    private BashTool tool() {
        return new BashTool(new PathResolver(root), true, POSIX_SHELL, null, 4);
    }

    private static ObjectNode cmd(String command, boolean low) {
        ObjectNode n = MAPPER.createObjectNode().put("command", command);
        return low ? n.put("priority", "low") : n;
    }

    @Test
    void uneSondeQuiTermineRendSonCodeEtSonCpu() {
        ToolOutcome out = tool().run(cmd("echo relevé", true), ToolContext.none());
        assertTrue(out.ok());
        assertEquals(0, out.exitCode());
        assertNotNull(out.cpuMs(), "Le CPU d'une commande de basse priorité est remonté (Z5)");
    }

    @Test
    void refuseeQuandUneCommandeDuPosteTourne() throws Exception {
        BashTool tool = tool();
        CompletableFuture<ToolOutcome> normal = CompletableFuture.supplyAsync(
                () -> tool.run(cmd("sleep 1", false), ToolContext.none()));
        Thread.sleep(300);
        ToolOutcome low = tool.run(cmd("echo x", true), ToolContext.none());
        assertFalse(low.ok());
        assertEquals(BashTool.BUSY_DEFERRED, low.errorCode());
        assertTrue(normal.get(10, TimeUnit.SECONDS).ok());
    }

    @Test
    void uneSeuleSondeALaFois() throws Exception {
        BashTool tool = tool();
        CompletableFuture<ToolOutcome> first = CompletableFuture.supplyAsync(
                () -> tool.run(cmd("sleep 1", true), ToolContext.none()));
        Thread.sleep(300);
        assertEquals(BashTool.BUSY_DEFERRED, tool.run(cmd("echo x", true), ToolContext.none()).errorCode());
        first.get(10, TimeUnit.SECONDS);
    }

    @Test
    void preempteeEnMoinsDe500msQuandLePOLanceUneCommande() throws Exception {
        BashTool tool = tool();
        CompletableFuture<ToolOutcome> low = CompletableFuture.supplyAsync(
                () -> tool.run(cmd("sleep 10", true), ToolContext.none()));
        Thread.sleep(400);
        long t0 = System.nanoTime();
        ToolOutcome normal = tool.run(cmd("echo moi", false), ToolContext.none());
        ToolOutcome cut = low.get(5, TimeUnit.SECONDS);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(normal.ok(), "La commande du PO passe, immédiatement");
        assertFalse(cut.ok());
        assertEquals(BashTool.PREEMPTED, cut.errorCode());
        assertTrue(ms <= 500, "Préemption en " + ms + " ms (≤ 500 ms)");
    }

    @Test
    void laLigneEstEnPrioriteBasseLaOuNiceExiste() {
        var line = BashTool.lowPriorityLine(java.util.List.of("/bin/sh", "-c", "echo"));
        if (Files.isExecutable(Path.of("/usr/bin/nice")) || Files.isExecutable(Path.of("/bin/nice"))) {
            assertTrue(line.get(0).endsWith("nice"));
            assertEquals("19", line.get(2));
        }
    }

    @Test
    void laCapaciteEstAnnoncee() {
        assertEquals("low_priority", BashTool.LOW_PRIORITY_CAPABILITY);
    }
}
