package fr.claudegateway.runner.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.Console;
import fr.claudegateway.runner.FrameSender;
import fr.claudegateway.runner.RunnerBuild;
import fr.claudegateway.runner.launcher.LauncherHome;

/** F-111 / SF-111-04 — la commande de mise à jour, côté runner, avec une sortie injectée. */
class RunnerUpdaterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RunnerBuild CURRENT = RunnerBuild.parseId("1.0.0-202609130000-aaaaaaa").orElseThrow();
    private static final String NEXT = "1.1.0-202609200900-bbbbbbb";

    @TempDir
    Path dir;

    private final List<JsonNode> frames = new CopyOnWriteArrayList<>();
    private final AtomicInteger exitCode = new AtomicInteger(-1);
    private final List<String> busy = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> installed = new AtomicReference<>();
    private FrameSender sender;
    private LauncherHome home;

    @BeforeEach
    void setUp() {
        home = new LauncherHome(dir);
        sender = new FrameSender(new Console());
        sender.attach(frame -> {
            try {
                frames.add(MAPPER.readTree(frame));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    @AfterEach
    void tearDown() {
        sender.close();
    }

    private RunnerUpdater updater(boolean underLauncher, RunnerUpdater.Installer installer) {
        RunnerUpdater updater = new RunnerUpdater(installer, home, underLauncher, CURRENT, () -> new ArrayList<>(busy),
                exitCode::set, new Console(), Duration.ofMillis(20), Duration.ofMillis(50));
        updater.attach(sender);
        return updater;
    }

    private RunnerUpdater.Installer fakeInstaller() {
        return (id, sha) -> {
            installed.set(id + "/" + sha);
            try {
                return home.install(id, new byte[] { 1 });
            } catch (java.io.IOException e) {
                throw new UpdateRejectedException(UpdateRejectedException.INSTALL_FAILED, e.getMessage());
            }
        };
    }

    private static JsonNode command(String version, boolean force) throws Exception {
        return MAPPER.readTree("{\"type\":\"update\",\"updateId\":\"u-1\",\"version\":\"" + version
                + "\",\"sha256\":\"abc\",\"force\":" + force + "}");
    }

    private List<String> states() {
        List<String> states = new ArrayList<>();
        frames.forEach(frame -> states.add(frame.path("state").asText()));
        return states;
    }

    private void awaitExit() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (exitCode.get() < 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void installsWaitsForCalmWritesNextVersionAndExitsWith75() throws Exception {
        busy.add("commande");
        RunnerUpdater updater = updater(true, fakeInstaller());

        updater.onUpdate(command(NEXT, false));
        Thread.sleep(300);
        assertEquals(-1, exitCode.get(), "une commande tourne : le runner attend");
        assertTrue(home.nextVersion().isEmpty(), "next-version n'est écrit qu'au moment de sortir");
        busy.clear();
        awaitExit();

        assertEquals(75, exitCode.get());
        assertEquals(NEXT + "/abc", installed.get(), "l'empreinte de la commande est transmise à l'installation");
        assertEquals(NEXT, home.nextVersion().orElseThrow());
        List<String> states = states();
        assertEquals("downloading", states.get(0));
        assertTrue(states.contains("waiting"), states.toString());
        assertEquals("restarting", states.get(states.size() - 1));
        JsonNode waiting = frames.stream().filter(f -> f.path("state").asText().equals("waiting")).findFirst()
                .orElseThrow();
        assertEquals("commande", waiting.path("busy").get(0).asText());
        assertEquals("u-1", waiting.path("updateId").asText());
    }

    @Test
    void forceStopsWaiting() throws Exception {
        busy.add("capture");
        RunnerUpdater updater = updater(true, fakeInstaller());

        updater.onUpdate(command(NEXT, false));
        Thread.sleep(200);
        updater.onUpdate(command(NEXT, true));
        awaitExit();

        assertEquals(75, exitCode.get(), "« Forcer » lève l'attente même si la capture tourne");
    }

    @Test
    void aRejectedDownloadInstallsNothingAndSaysWhy() throws Exception {
        RunnerUpdater updater = updater(true, (id, sha) -> {
            throw new UpdateRejectedException(UpdateRejectedException.SIGNATURE_INVALID, "signature invalide");
        });

        updater.run(RunnerUpdater.Order.of(command(NEXT, false)));
        Thread.sleep(100);

        assertEquals(-1, exitCode.get());
        assertTrue(home.nextVersion().isEmpty());
        JsonNode failed = frames.get(frames.size() - 1);
        assertEquals("failed", failed.path("state").asText());
        assertEquals("signature_invalid", failed.path("reason").asText());
    }

    @Test
    void theFrameRouterOfBothTransportsHandsTheCommandOver() throws Exception {
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        try (ToolDispatcherHolder holder = new ToolDispatcherHolder(sender)) {
            new fr.claudegateway.runner.FrameRouter(holder.dispatcher, new Console(), received::add)
                    .route("{\"type\":\"update\",\"version\":\"" + NEXT + "\"}");
        }
        assertEquals(1, received.size());
        assertEquals(NEXT, received.get(0).path("version").asText());
    }

    /** Un aiguilleur d'outils minimal, fermé après usage. */
    private static final class ToolDispatcherHolder implements AutoCloseable {
        final fr.claudegateway.runner.ToolDispatcher dispatcher;

        ToolDispatcherHolder(FrameSender sender) {
            dispatcher = new fr.claudegateway.runner.ToolDispatcher(
                    (tool, input, context) -> fr.claudegateway.runner.ToolOutcome.ok(""), sender, new Console());
        }

        @Override
        public void close() {
            dispatcher.close();
        }
    }

    @Test
    void withoutLauncherNothingIsDone() throws Exception {
        RunnerUpdater updater = updater(false, fakeInstaller());

        updater.onUpdate(command(NEXT, false));
        Thread.sleep(100);

        assertEquals(RunnerUpdater.NO_LAUNCHER, frames.get(0).path("reason").asText());
        assertEquals(null, installed.get());
        assertEquals(-1, exitCode.get());
    }

    @Test
    void anOlderOrEqualVersionIsRefused() throws Exception {
        RunnerUpdater updater = updater(true, fakeInstaller());

        updater.onUpdate(command(CURRENT.id(), false));
        updater.onUpdate(command("0.9.0-202601010000-zzz", false));
        updater.onUpdate(command("../../etc", false));
        Thread.sleep(100);

        assertEquals(3, frames.size());
        frames.forEach(frame -> assertEquals(RunnerUpdater.NOT_NEWER, frame.path("reason").asText()));
        assertEquals(null, installed.get());
        assertFalse(home.nextVersion().isPresent());
    }
}
