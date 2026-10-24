package fr.claudegateway.runner.video.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.OperatingSystem;
import fr.claudegateway.runner.PathResolver;
import fr.claudegateway.runner.ToolContext;
import fr.claudegateway.runner.ToolOutcome;
import fr.claudegateway.runner.teams.LocalToolchain;
import fr.claudegateway.runner.teams.ToolchainUnavailableException;

/**
 * Rendre une vidéo sur le poste (F-215 / SF-215-02) : processus, téléchargements et PATH simulés — rien n'est
 * lancé ni téléchargé pour de vrai.
 */
class RenderToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path temp;

    private Path project;
    private Path toolsDir;
    private Path systemNode;
    private final FakeProcesses processes = new FakeProcesses();
    private final List<String> downloads = new ArrayList<>();
    private boolean downloadFails;
    private boolean nodeOnPath = true;
    private boolean npmOnPath = true;
    private Optional<Path> systemChrome = Optional.empty();
    private boolean ffmpegMissing;
    private PathResolver paths;

    @BeforeEach
    void setUp() throws IOException {
        project = Files.createDirectories(temp.resolve("projet"));
        toolsDir = temp.resolve("tools");
        systemNode = Files.createDirectories(temp.resolve("sys/bin")).resolve("node");
        Files.writeString(systemNode, "");
        Path cours = Files.createDirectories(project.resolve("cours"));
        Files.writeString(cours.resolve("index.html"), "<html></html>");
        paths = new PathResolver(project);
    }

    private RenderTools tools() {
        LocalToolchain.Downloader downloader = (url, into) -> {
            downloads.add(url);
            if (downloadFails) {
                throw new IOException("connexion refusée par le proxy");
            }
            Files.writeString(into, "archive");
            return 7;
        };
        RenderToolchain.Media media = new RenderToolchain.Media() {
            @Override
            public Path ffmpeg() {
                if (ffmpegMissing) {
                    throw new ToolchainUnavailableException("ffmpeg introuvable", "sudo apt install ffmpeg");
                }
                return Path.of("/usr/bin/ffmpeg");
            }

            @Override
            public Path ffprobe() {
                return Path.of("/usr/bin/ffprobe");
            }
        };
        RenderToolchain toolchain = new RenderToolchain(toolsDir, OperatingSystem.LINUX, "amd64", processes, downloader,
                name -> switch (name) {
                    case "node" -> nodeOnPath ? systemNode : null;
                    case "npm" -> npmOnPath ? Path.of("/usr/bin/npm") : null;
                    default -> null;
                }, media, os -> systemChrome, "/usr/bin");
        return new RenderTools(toolchain, processes, new DirectExecutor());
    }

    private ObjectNode input() {
        return MAPPER.createObjectNode().put("dossier", "cours");
    }

    private static String jobId(ToolOutcome outcome) {
        for (String line : outcome.content().split("\n")) {
            if (line.startsWith("job_id : ")) {
                return line.substring("job_id : ".length()).strip();
            }
        }
        throw new AssertionError("pas de job_id : " + outcome.content());
    }

    private ToolOutcome status(RenderTools tools, String id) {
        return tools.execute(RenderTools.STATUS, MAPPER.createObjectNode().put("job_id", id), ToolContext.none(), paths);
    }

    @Test
    void nominalPrepareThenDetachedRenderThenDone() {
        RenderTools tools = tools();

        ToolOutcome started = tools.execute(RenderTools.RENDER, input().put("fps", 30), ToolContext.none(), paths);

        assertTrue(started.ok(), started.errorMessage());
        List<String> launch = processes.launched.get(0);
        assertEquals(systemNode.toString(), launch.get(0));
        assertTrue(launch.get(1).endsWith("hyperframes.mjs"));
        assertEquals("render", launch.get(2));
        assertTrue(launch.containsAll(List.of("-o", "--quality", "looks", "--fps", "30")), launch.toString());
        assertTrue(launch.contains(project.resolve("cours/renders/cours.mp4").toString()), launch.toString());
        Map<String, String> env = processes.launchedEnv.get(0);
        assertEquals("1", env.get("HYPERFRAMES_RENDER_DETACHED"));
        assertEquals("1", env.get("HYPERFRAMES_NO_TELEMETRY"));
        assertEquals("/usr/bin/ffmpeg", env.get("HYPERFRAMES_FFMPEG_PATH"));
        assertEquals("/usr/bin/ffprobe", env.get("HYPERFRAMES_FFPROBE_PATH"));
        assertTrue(processes.ran.stream().anyMatch(c -> c.contains("hyperframes@0.8.193")), "npm install épinglé");
        assertTrue(processes.ran.stream().anyMatch(c -> c.containsAll(List.of("browser", "ensure"))));
        assertTrue(downloads.isEmpty(), "Node 22 du PATH réutilisé, rien rapatrié");

        String id = jobId(started);
        processes.alive = false;
        ToolOutcome done = status(tools, id);
        assertTrue(done.content().contains("étape : Terminé"), done.content());
        assertTrue(done.content().contains("video_publier"), done.content());
    }

    @Test
    void oldNodeOnPathTriggersPinnedDownload() {
        processes.systemNodeVersion = "v18.19.0";
        RenderTools tools = tools();

        ToolOutcome started = tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths);

        assertTrue(started.ok(), started.errorMessage());
        assertEquals(List.of("https://nodejs.org/dist/v22.22.1/node-v22.22.1-linux-x64.tar.xz"), downloads);
        assertEquals(toolsDir.resolve("node-v22.22.1/bin/node").toString(),
                processes.launched.get(0).get(0));
    }

    @Test
    void downloadFailureIsNamedWithRemedy() {
        nodeOnPath = false;
        downloadFails = true;
        RenderTools tools = tools();

        String id = jobId(tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths));
        String text = status(tools, id).content();

        assertTrue(text.contains("étape : Échec"), text);
        assertTrue(text.contains("rapatriement depuis nodejs.org a échoué"), text);
        assertTrue(text.contains("remède : Installez Node.js 22 LTS"), text);
        assertTrue(processes.launched.isEmpty());
    }

    @Test
    void refusedExecutionIsThePostePolicyAndNothingElseIsTried() {
        nodeOnPath = false;
        processes.managedNodeRefused = true;
        RenderTools tools = tools();

        String id = jobId(tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths));
        String text = status(tools, id).content();

        assertTrue(text.contains("politique du poste, je ne la contourne pas"), text);
        assertTrue(text.contains("support"), text);
        assertFalse(processes.ran.stream().anyMatch(c -> c.contains("install")), "aucune autre tentative");
        assertTrue(processes.launched.isEmpty());
    }

    @Test
    void npmRefusedIsNamedWithRemedy() {
        processes.npmFails = true;
        RenderTools tools = tools();

        String id = jobId(tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths));
        String text = status(tools, id).content();

        assertTrue(text.contains("Installation de hyperframes 0.8.193 impossible"), text);
        assertTrue(text.contains("403 Forbidden"), text);
        assertTrue(text.contains("registre npm"), text);
    }

    @Test
    void browserFallsBackToInstalledChromeOrFailsClearly() {
        processes.browserFails = true;
        RenderTools failing = tools();
        String failed = status(failing, jobId(failing.execute(RenderTools.RENDER, input(), ToolContext.none(), paths)))
                .content();
        assertTrue(failed.contains("Chrome sans écran indisponible"), failed);

        systemChrome = Optional.of(Path.of("/usr/bin/google-chrome"));
        RenderTools fallback = tools();
        ToolOutcome started = fallback.execute(RenderTools.RENDER, input().put("sortie", "out/autre"),
                ToolContext.none(), paths);
        assertTrue(started.ok(), started.errorMessage());
        assertEquals("/usr/bin/google-chrome", processes.launchedEnv.get(0).get("HYPERFRAMES_BROWSER_PATH"));
        assertTrue(processes.launched.get(0).contains(project.resolve("out/autre.mp4").toString()));
    }

    @Test
    void ffmpegMissingIsNamed() {
        ffmpegMissing = true;
        RenderTools tools = tools();
        String text = status(tools, jobId(tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths)))
                .content();
        assertTrue(text.contains("ffmpeg indisponible"), text);
        assertTrue(text.contains("sudo apt install ffmpeg"), text);
    }

    @Test
    void invalidInputsAreRefused() {
        RenderTools tools = tools();
        assertEquals("invalid_input", tools.execute(RenderTools.RENDER, MAPPER.createObjectNode(), ToolContext.none(),
                paths).errorCode());
        assertEquals("not_found", tools.execute(RenderTools.RENDER, MAPPER.createObjectNode().put("dossier", "absent"),
                ToolContext.none(), paths).errorCode());
        assertEquals("not_found", tools.execute(RenderTools.RENDER, input().put("composition", "intro.html"),
                ToolContext.none(), paths).errorCode());
        assertEquals("not_found", tools.execute(RenderTools.RENDER, input().put("composition", "../../x.html"),
                ToolContext.none(), paths).errorCode());
        assertEquals("invalid_input", tools.execute(RenderTools.RENDER, input().put("qualite", "ultra"),
                ToolContext.none(), paths).errorCode());
        assertEquals("invalid_input", tools.execute(RenderTools.RENDER, input().put("fps", 50), ToolContext.none(),
                paths).errorCode());
        assertEquals("invalid_input", tools.execute(RenderTools.RENDER, input().put("workers", 9), ToolContext.none(),
                paths).errorCode());
        assertTrue(processes.ran.isEmpty());
    }

    @Test
    void sameOutputRunningReturnsTheSameJob() {
        processes.completeOnLaunch = false;
        RenderTools tools = tools();
        String first = jobId(tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths));
        ToolOutcome again = tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths);
        assertEquals(first, jobId(again));
        assertTrue(again.content().contains("déjà en cours"));
        assertEquals(1, processes.launched.size());
    }

    @Test
    void progressIsReadFromTheLogAndAStoppedRenderWithoutVideoFails() throws IOException {
        processes.completeOnLaunch = false;
        RenderTools tools = tools();
        String id = jobId(tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths));
        Path log = project.resolve(".atelier/rendu/" + id + "/render.log");
        Files.writeString(log, "capture 12%\rcapture 37%\r", java.nio.file.StandardOpenOption.APPEND);

        String running = status(tools, id).content();
        assertTrue(running.contains("avancement : 37 %"), running);
        assertTrue(running.contains("Toujours en cours"), running);

        processes.alive = false;
        String stopped = status(tools, id).content();
        assertTrue(stopped.contains("étape : Échec"), stopped);
        assertTrue(stopped.contains("sans produire de vidéo"), stopped);
    }

    @Test
    void statusSurvivesARunnerRestartAndUnknownJobsAreRefused() {
        processes.completeOnLaunch = false;
        String id = jobId(tools().execute(RenderTools.RENDER, input(), ToolContext.none(), paths));

        RenderTools restarted = tools();
        assertTrue(status(restarted, id).content().contains("étape : Rendu en cours"));
        assertEquals("not_found", status(restarted, "0123456789ab").errorCode());
        assertEquals("invalid_input", status(restarted, "nope").errorCode());
    }

    @Test
    void checkOnlyPreparesWithoutRendering() {
        RenderTools tools = tools();
        String id = jobId(tools.execute(RenderTools.RENDER, MAPPER.createObjectNode().put("verifier_seulement", true),
                ToolContext.none(), paths));
        String text = status(tools, id).content();
        assertTrue(text.contains("La chaîne de rendu est prête"), text);
        assertTrue(processes.launched.isEmpty());
    }

    @Test
    void parsesNodeMajor() {
        assertEquals(22, RenderToolchain.parseMajor("v22.22.1"));
        assertEquals(18, RenderToolchain.parseMajor("v18.19.0\n"));
        assertEquals(-1, RenderToolchain.parseMajor("command not found"));
    }

    // ------------------------------------------------------------------ fakes

    /** Exécute la tâche dans le fil de l'appel : le test lit l'état juste après. */
    static final class DirectExecutor extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    final class FakeProcesses implements RenderProcesses {
        final List<List<String>> ran = new ArrayList<>();
        final List<List<String>> launched = new ArrayList<>();
        final List<Map<String, String>> launchedEnv = new ArrayList<>();
        String systemNodeVersion = "v22.3.0";
        boolean managedNodeRefused;
        boolean npmFails;
        boolean browserFails;
        boolean completeOnLaunch = true;
        boolean alive = true;

        @Override
        public Result run(List<String> command, Path workingDir, Map<String, String> env, long timeoutMs)
                throws IOException {
            ran.add(command);
            String program = command.get(0);
            if (command.contains("--version")) {
                if (program.equals(systemNode.toString())) {
                    return new Result(0, List.of(systemNodeVersion), false);
                }
                if (managedNodeRefused) {
                    throw new IOException("Cannot run program: error=13, Permission denied");
                }
                return new Result(0, List.of("v22.22.1"), false);
            }
            if ("tar".equals(program)) {
                Path into = Path.of(command.get(command.indexOf("-C") + 1));
                Path bin = Files.createDirectories(into.resolve("node-v22.22.1-linux-x64/bin"));
                Files.writeString(bin.resolve("node"), "");
                return new Result(0, List.of(), false);
            }
            if (command.contains("install")) {
                if (npmFails) {
                    return new Result(1, List.of("npm error code E403", "npm error 403 Forbidden"), false);
                }
                Path prefix = Path.of(command.get(command.indexOf("--prefix") + 1));
                Path pkg = Files.createDirectories(prefix.resolve("node_modules/hyperframes/bin"));
                Files.writeString(pkg.resolve("hyperframes.mjs"), "");
                Files.writeString(prefix.resolve("node_modules/hyperframes/package.json"),
                        "{\"name\":\"hyperframes\",\"version\":\"0.8.193\"}");
                return new Result(0, List.of(), false);
            }
            if (command.contains("browser")) {
                return browserFails ? new Result(1, List.of("download blocked"), false) : new Result(0, List.of(), false);
            }
            return new Result(0, List.of(), false);
        }

        @Override
        public long launchDetached(List<String> command, Path workingDir, Map<String, String> env, Path log)
                throws IOException {
            launched.add(command);
            launchedEnv.add(new HashMap<>(env));
            Files.createDirectories(log.getParent());
            if (completeOnLaunch) {
                Path output = Path.of(command.get(command.indexOf("-o") + 1));
                Files.write(output, new byte[] {0, 0, 0, 24});
                Files.writeString(log, "Render complete\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
            return 4242L;
        }

        @Override
        public boolean alive(long pid) {
            return alive;
        }
    }
}
