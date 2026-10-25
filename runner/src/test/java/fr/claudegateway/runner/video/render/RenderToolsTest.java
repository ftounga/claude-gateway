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
import fr.claudegateway.runner.teams.ToolchainUnavailableException;

/**
 * Rendre une vidéo sur le poste (F-215 / SF-215-02, SF-215-06) : processus et PATH simulés ; le portail est une adresse
 * morte (le téléchargement réel du paquet est éprouvé par {@code VideoToolchainBundleTest}, et la chaîne entière,
 * hors ligne, par {@code VideoToolchainLiveTest}).
 */
class RenderToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path temp;

    private Path project;
    private Path toolsDir;
    private Path systemNode;
    private Path chrome;
    private final FakeProcesses processes = new FakeProcesses();
    private boolean nodeOnPath = true;
    private Optional<Path> systemChrome = Optional.empty();
    private boolean ffmpegMissing;
    private PathResolver paths;

    @BeforeEach
    void setUp() throws IOException {
        project = Files.createDirectories(temp.resolve("projet"));
        toolsDir = temp.resolve("tools");
        systemNode = Files.createDirectories(temp.resolve("sys/bin")).resolve("node");
        Files.writeString(systemNode, "");
        chrome = Files.writeString(Files.createDirectories(temp.resolve("opt/google/chrome")).resolve("chrome"), "");
        systemChrome = Optional.of(chrome);
        Path cours = Files.createDirectories(project.resolve("cours"));
        Files.writeString(cours.resolve("index.html"), "<html></html>");
        paths = new PathResolver(project);
        installBundle();
    }

    private void removeBundle() {
        try (java.util.stream.Stream<Path> walk = Files.walk(toolsDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Le paquet du portail déjà en place dans le dossier d'outils (marqueur compris). */
    private Path installBundle() throws IOException {
        Path home = toolsDir.resolve("video-toolchain-" + VideoToolchainBundle.VERSION);
        for (String file : List.of("node/bin/node", "hyperframes/node_modules/hyperframes/bin/hyperframes.mjs",
                "hyperframes/node_modules/gsap/dist/gsap.min.js", "ffmpeg/ffmpeg", "ffmpeg/ffprobe")) {
            Path path = home.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.endsWith("gsap.min.js") ? "/* GSAP 3.13.0 */" : "");
        }
        Files.writeString(home.resolve(".complete"), "sha256\n");
        return home;
    }

    /** hyperframes (et GSAP) installés par une version précédente du runner. */
    private void installLegacyHyperframes(boolean withGsap) throws IOException {
        Path dir = toolsDir.resolve("hyperframes-0.8.193/node_modules");
        Path hf = Files.createDirectories(dir.resolve("hyperframes/bin"));
        Files.writeString(hf.resolve("hyperframes.mjs"), "");
        Files.writeString(dir.resolve("hyperframes/package.json"), "{\"version\":\"0.8.193\"}");
        if (withGsap) {
            Path gsap = Files.createDirectories(dir.resolve("gsap/dist"));
            Files.writeString(gsap.resolve("gsap.min.js"), "/* GSAP 3.13.0 */");
            Files.writeString(dir.resolve("gsap/package.json"), "{\"version\":\"3.13.0\"}");
        }
    }

    private RenderTools tools() {
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

            @Override
            public boolean presentWithoutDownload() {
                return !ffmpegMissing;
            }
        };
        // Le portail : une adresse morte. Un test qui n'installe pas le paquet prouve ainsi qu'il n'en a pas besoin,
        // ou voit l'échec nommé du portail injoignable.
        VideoToolchainBundle bundle = new VideoToolchainBundle(toolsDir, OperatingSystem.LINUX, "amd64",
                "http://127.0.0.1:1/api", () -> "jeton", java.net.http.HttpClient::newHttpClient, (m, s) -> true);
        RenderToolchain toolchain = new RenderToolchain(toolsDir, OperatingSystem.LINUX, processes, bundle,
                name -> switch (name) {
                    case "node" -> nodeOnPath ? systemNode : null;
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

    /** Aucune commande n'installe, ne télécharge un navigateur, ni ne joint internet (SF-215-06). */
    private void assertNothingFetched() {
        assertFalse(processes.ran.stream().anyMatch(c -> c.contains("install") || c.contains("ensure")
                || c.stream().anyMatch(a -> a.contains("npm") || a.contains("nodejs.org"))), processes.ran.toString());
    }

    @Test
    void nominalPrepareFromThePortalBundleThenDetachedRenderThenDone() throws IOException {
        Path home = installBundle();
        RenderTools tools = tools();

        ToolOutcome started = tools.execute(RenderTools.RENDER, input().put("fps", 30), ToolContext.none(), paths);

        assertTrue(started.ok(), started.errorMessage());
        List<String> launch = processes.launched.get(0);
        assertEquals(home.resolve("node/bin/node").toString(), launch.get(0));
        assertTrue(launch.get(1).endsWith("hyperframes.mjs"));
        assertEquals("render", launch.get(2));
        assertTrue(launch.containsAll(List.of("-o", "--quality", "looks", "--fps", "30")), launch.toString());
        assertTrue(launch.contains(project.resolve("cours/renders/cours.mp4").toString()), launch.toString());
        Map<String, String> env = processes.launchedEnv.get(0);
        assertEquals("1", env.get("HYPERFRAMES_RENDER_DETACHED"));
        assertEquals(home.resolve("ffmpeg/ffmpeg").toString(), env.get("HYPERFRAMES_FFMPEG_PATH"));
        assertEquals(home.resolve("ffmpeg/ffprobe").toString(), env.get("HYPERFRAMES_FFPROBE_PATH"));
        assertEquals(chrome.toString(), env.get("HYPERFRAMES_BROWSER_PATH"), "le Chrome du poste");
        assertNothingFetched();

        String id = jobId(started);
        processes.alive = false;
        ToolOutcome done = status(tools, id);
        assertTrue(done.content().contains("étape : Terminé"), done.content());
        assertTrue(done.content().contains("video_publier"), done.content());
        assertTrue(done.content().contains("chaîne vidéo du portail"), done.content());
    }

    @Test
    void hyperframesNeverPhonesHome() throws IOException {
        installBundle();
        RenderTools tools = tools();

        tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths);

        Map<String, String> env = processes.launchedEnv.get(0);
        for (String name : List.of("HYPERFRAMES_NO_TELEMETRY", "DO_NOT_TRACK", "HYPERFRAMES_NO_UPDATE_CHECK",
                "HYPERFRAMES_NO_AUTO_INSTALL", "HYPERFRAMES_SKIP_SKILLS", "HYPERFRAMES_NO_OPTIONAL_INSTALL")) {
            assertEquals("1", env.get(name), name);
        }
    }

    @Test
    void node22AndHyperframesAlreadyOnThePosteAreUsedWithoutAnyDownload() throws IOException {
        removeBundle();
        installLegacyHyperframes(true);
        RenderTools tools = tools();

        ToolOutcome started = tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths);

        assertTrue(started.ok(), started.errorMessage());
        assertEquals(systemNode.toString(), processes.launched.get(0).get(0), "Node 22 du PATH");
        assertEquals("/usr/bin/ffmpeg", processes.launchedEnv.get(0).get("HYPERFRAMES_FFMPEG_PATH"));
        assertNothingFetched();
    }

    @Test
    void oldNodeOnPathFallsToThePortalAndAnUnreachablePortalIsNamedWithoutBrewNorNodejs() throws IOException {
        removeBundle();
        installLegacyHyperframes(true);
        processes.systemNodeVersion = "v18.19.0";
        RenderTools tools = tools();

        String id = jobId(tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths));
        String text = status(tools, id).content();

        assertTrue(text.contains("étape : Échec"), text);
        assertTrue(text.contains("Le portail n'a pas pu être joint"), text);
        assertFalse(text.contains("brew") || text.contains("nodejs.org"), text);
        assertTrue(processes.launched.isEmpty());
        assertNothingFetched();
        String log = Files.readString(project.resolve(".atelier/rendu/" + id + "/render.log"));
        assertTrue(log.contains("Node 18 trouvé sur ce poste"), log);
    }

    @Test
    void refusedExecutionIsThePostePolicyAndNothingElseIsTried() throws IOException {
        installBundle();
        processes.managedNodeRefused = true;
        RenderTools tools = tools();

        String id = jobId(tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths));
        String text = status(tools, id).content();

        assertTrue(text.contains("politique du poste, je ne la contourne pas"), text);
        assertTrue(text.contains("support"), text);
        assertFalse(text.contains("brew"), text);
        assertNothingFetched();
        assertTrue(processes.launched.isEmpty());
    }

    @Test
    void withoutChromeNorEdgeTheFailureIsActionable() throws IOException {
        installBundle();
        systemChrome = Optional.empty();
        RenderTools none = tools();
        String failed = status(none, jobId(none.execute(RenderTools.RENDER, input(), ToolContext.none(), paths)))
                .content();
        assertTrue(failed.contains("Aucun Chrome ni Edge trouvé"), failed);
        assertTrue(failed.contains("CLAUDE_TEAMS_CHROME_PATH"), failed);
        assertNothingFetched();

        systemChrome = Optional.of(temp.resolve("absent/chrome"));
        RenderTools wrong = tools();
        String wrongPath = status(wrong, jobId(wrong.execute(RenderTools.RENDER, input().put("sortie", "out/autre"),
                ToolContext.none(), paths))).content();
        assertTrue(wrongPath.contains("introuvable"), wrongPath);
    }

    @Test
    void withoutFfmpegOnThePosteTheLocalChainIsNotRetainedAndThePortalIsAsked() throws IOException {
        removeBundle();
        installLegacyHyperframes(true);
        ffmpegMissing = true;
        RenderTools tools = tools();
        String text = status(tools, jobId(tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths)))
                .content();
        assertTrue(text.contains("portail"), text);
        assertFalse(text.contains("sudo apt install ffmpeg"), text);
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
        Files.writeString(log, "capture 12%\rcapture 37%\r", java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);

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

    // ------------------------------------------------------------------ SF-215-03 : GSAP et relecture

    private ObjectNode check(String instants) {
        return MAPPER.createObjectNode().put("dossier", "cours").put("instants", instants);
    }

    @Test
    void gsapOfTheChainIsCopiedIntoTheCompositionBeforeRendering() {
        RenderTools tools = tools();

        ToolOutcome started = tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths);

        assertTrue(started.ok(), started.errorMessage());
        assertTrue(Files.isRegularFile(project.resolve("cours/briques/gsap.min.js")));
        assertNothingFetched();
    }

    @Test
    void aPreviousHyperframesWithoutGsapIsNotEnoughAndThePortalIsAsked() throws IOException {
        removeBundle();
        installLegacyHyperframes(false);
        RenderTools tools = tools();

        String text = status(tools, jobId(tools.execute(RenderTools.RENDER, input(), ToolContext.none(), paths)))
                .content();

        assertTrue(text.contains("portail"), text);
        assertNothingFetched();
    }

    @Test
    void checkLintsThenSnapshotsAndListsTheImagesToLookAt() {
        RenderTools tools = tools();

        ToolOutcome outcome = tools.execute(RenderTools.CHECK, check("3, 10.5"), ToolContext.none(), paths);

        assertTrue(outcome.ok(), outcome.errorMessage());
        String text = outcome.content();
        assertTrue(text.contains("LINT : OK"), text);
        assertTrue(text.contains("1 warning"), text);
        assertTrue(text.contains("- cours/relecture/frame-00-at-3s.png\n- cours/relecture/frame-01-at-10.50s.png"), text);
        assertFalse(text.contains("contact-sheet"), text);
        assertTrue(text.contains("read_file"), text);
        List<String> snapshot = processes.ran.stream().filter(c -> c.contains("snapshot")).findFirst().orElseThrow();
        assertTrue(snapshot.containsAll(List.of("--at", "3,10.50", "--no-end", "--describe", "false", "-o")), snapshot.toString());
        assertEquals("", processes.lintEnv.get("GEMINI_API_KEY"), "aucune analyse d'image confiée à un tiers");
        assertTrue(Files.isRegularFile(project.resolve("cours/briques/gsap.min.js")), "gsap copié avant le contrôle");
    }

    @Test
    void lintErrorsAreSaidAndColorsStripped() {
        processes.lintFails = true;
        RenderTools tools = tools();

        String text = tools.execute(RenderTools.CHECK, check("2"), ToolContext.none(), paths).content();

        assertTrue(text.contains("LINT : ERREURS"), text);
        assertTrue(text.contains("✗ error audio-missing-id"), text);
        assertFalse(text.contains("\u001B["), text);
    }

    @Test
    void failedSnapshotAnnouncesNoImage() {
        processes.snapshotFails = true;
        RenderTools tools = tools();

        String text = tools.execute(RenderTools.CHECK, check("2"), ToolContext.none(), paths).content();

        assertTrue(text.contains("IMAGES : échec de la capture"), text);
        assertTrue(text.contains("Timed out"), text);
        assertFalse(text.contains(".png"), text);
    }

    @Test
    void checkRefusesBadInputs() throws IOException {
        RenderTools tools = tools();
        Files.createDirectories(project.resolve("vide"));

        assertEquals("invalid_input", tools.execute(RenderTools.CHECK, check(""), ToolContext.none(), paths).errorCode());
        assertEquals("invalid_input", tools.execute(RenderTools.CHECK, check("abc"), ToolContext.none(), paths).errorCode());
        assertEquals("invalid_input", tools.execute(RenderTools.CHECK, check("-1"), ToolContext.none(), paths).errorCode());
        assertEquals("invalid_input", tools.execute(RenderTools.CHECK, check("1,2,3,4,5,6,7,8,9,10,11,12,13"),
                ToolContext.none(), paths).errorCode());
        assertEquals("invalid_input", tools.execute(RenderTools.CHECK,
                MAPPER.createObjectNode().put("instants", "2"), ToolContext.none(), paths).errorCode());
        assertEquals("not_found", tools.execute(RenderTools.CHECK,
                MAPPER.createObjectNode().put("dossier", "vide").put("instants", "2"), ToolContext.none(), paths)
                .errorCode());
        assertTrue(processes.ran.isEmpty(), "rien n'est lancé sur une demande invalide");
    }

    @Test
    void checkWithABrokenChainSaysWhatAndTheRemedy() {
        removeBundle();
        RenderTools tools = tools();

        ToolOutcome outcome = tools.execute(RenderTools.CHECK, check("2"), ToolContext.none(), paths);

        assertFalse(outcome.ok());
        assertTrue(outcome.errorMessage().contains("Le portail n'a pas pu être joint"), outcome.errorMessage());
        assertTrue(outcome.errorMessage().contains("Remède"), outcome.errorMessage());
        assertFalse(outcome.errorMessage().contains("brew"), outcome.errorMessage());
    }

    @Test
    void checkDuringAFirstLongPreparationAsksToRetry() {
        removeBundle();
        VideoToolchainBundle bundle = new VideoToolchainBundle(toolsDir, OperatingSystem.LINUX, "amd64",
                "http://127.0.0.1:1/api", () -> "jeton", java.net.http.HttpClient::newHttpClient, (m, s) -> true);
        RenderToolchain toolchain = new RenderToolchain(toolsDir, OperatingSystem.LINUX, processes, bundle,
                name -> null, null, os -> Optional.empty(), "/usr/bin");
        // Un exécuteur qui ne lance jamais rien : la préparation « dure ».
        RenderTools tools = new RenderTools(toolchain, processes, new NeverExecutor());
        tools.prepareWaitMs = 50L;

        ToolOutcome outcome = tools.execute(RenderTools.CHECK, check("2"), ToolContext.none(), paths);

        assertTrue(outcome.ok(), outcome.errorMessage());
        assertTrue(outcome.content().contains("Relance video_render_check"), outcome.content());
        assertTrue(outcome.content().contains("depuis le portail"), outcome.content());
    }

    // ------------------------------------------------------------------ fakes

    /** N'exécute jamais rien : une préparation qui ne finit pas dans le délai. */
    static final class NeverExecutor extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
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
        boolean lintFails;
        boolean snapshotFails;
        Map<String, String> lintEnv;

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
                if (command.contains("gsap@3.13.0")) {
                    Path gsap = Files.createDirectories(prefix.resolve("node_modules/gsap/dist"));
                    Files.writeString(gsap.resolve("gsap.min.js"), "/* GSAP 3.13.0 */");
                    Files.writeString(prefix.resolve("node_modules/gsap/package.json"),
                            "{\"name\":\"gsap\",\"version\":\"3.13.0\"}");
                    return new Result(0, List.of(), false);
                }
                Path pkg = Files.createDirectories(prefix.resolve("node_modules/hyperframes/bin"));
                Files.writeString(pkg.resolve("hyperframes.mjs"), "");
                Files.writeString(prefix.resolve("node_modules/hyperframes/package.json"),
                        "{\"name\":\"hyperframes\",\"version\":\"0.8.193\"}");
                return new Result(0, List.of(), false);
            }
            if (command.contains("browser")) {
                return browserFails ? new Result(1, List.of("download blocked"), false) : new Result(0, List.of(), false);
            }
            if (command.contains("lint")) {
                lintEnv = new HashMap<>(env);
                return lintFails
                        ? new Result(1, List.of("\u001B[31m✗ error\u001B[0m audio-missing-id: <audio> sans id", ""), false)
                        : new Result(0, List.of("✓ 0 error, 1 warning"), false);
            }
            if (command.contains("snapshot")) {
                if (!snapshotFails) {
                    Path out = Files.createDirectories(Path.of(command.get(command.indexOf("-o") + 1)));
                    Files.writeString(out.resolve("frame-01-at-10.50s.png"), "png");
                    Files.writeString(out.resolve("frame-00-at-3s.png"), "png");
                    Files.writeString(out.resolve("contact-sheet-1.jpg"), "jpg");
                }
                return snapshotFails ? new Result(1, List.of("Timed out waiting for runtime"), false)
                        : new Result(0, List.of(), false);
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
