package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>L'enregistrement local rejoint le chemin existant</b> (F-91 / SF-91-03), vu depuis les outils.
 *
 * <p>C'est le point de la subfeature : une fois la vidéo et la transcription obtenues, <b>on ne
 * refait pas</b> les moments, la carte et le compte rendu — on y entre. Ce que ces tests
 * verrouillent est l'<b>entrée</b> : {@code capture_id} apporte la vidéo, les répliques et
 * l'origine du temps, et une capture qui tourne encore est refusée.</p>
 */
@DisplayName("F-91 / SF-91-03 — rejoindre le chemin existant")
class TeamsCaptureTranscriptionToolsTest {

    private static final Instant STARTED = Instant.parse("2026-09-13T14:32:00Z");

    @TempDir
    Path host;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> said = new ArrayList<>();
    private TeamsWorkFolder folder;
    private CaptureStore store;
    private FakeProcesses processes;
    private FakeSessions sessions;
    private LocalCapture capture;
    private TranscriptionWorker transcription;

    @BeforeEach
    void setUp() {
        folder = new TeamsWorkFolder(host);
        store = new CaptureStore(folder);
        processes = new FakeProcesses();
        sessions = new FakeSessions();
        said.clear();
        answerLikeRealBinaries();
        LocalToolchain toolchain = toolchain();
        capture = new LocalCapture(folder, OperatingSystem.LINUX, toolchain, processes, sessions,
                store, new WatermarkFont(OperatingSystem.LINUX,
                        path -> path.toString().endsWith("DejaVuSans.ttf")),
                millis -> { }, () -> STARTED, said::add, () -> ":0.0");
        transcription = new TranscriptionWorker(store, new AudioTrack(toolchain, processes),
                new LocalTranscription(toolchain, processes), directPool());
    }

    @Test
    @DisplayName("l'arrêt DÉMARRE la transcription, et le dit — sans attendre la fin")
    void stopStartsTheTranscription() throws IOException {
        JsonNode started = call(TeamsTools.CAPTURE_START, "{\"purpose\":\"self\"}");
        writeCsv(started.path("captureId").asText(),
                "start,end,text\n0,2400,\"Bonjour à tous.\"\n");

        JsonNode stopped = call(TeamsTools.CAPTURE_STOP, "{}");

        assertEquals("TERMINE", stopped.path("transcription").asText());
        assertEquals(1, stopped.path("cues").size());
        assertEquals("Bonjour à tous.", stopped.path("cues").get(0).path("text").asText());
        assertTrue(stopped.path("text").asText().toLowerCase(java.util.Locale.ROOT)
                .contains("sur cette machine"),
                stopped.path("text").asText());
    }

    @Test
    @DisplayName("sans transcription montée : l'outil le DIT, il ne laisse pas espérer un compte rendu")
    void withoutTranscriptionItSaysSo() throws IOException {
        TeamsTools tools = toolsWithoutTranscription();
        tools.execute(TeamsTools.CAPTURE_START, mapper.readTree("{\"purpose\":\"self\"}"));

        JsonNode stopped = mapper.readTree(
                tools.execute(TeamsTools.CAPTURE_STOP, mapper.readTree("{}")).content());

        assertEquals("ABSENTE", stopped.path("transcription").asText());
        assertTrue(stopped.path("text").asText().contains("n'aura pas de transcription"),
                stopped.path("text").asText());
    }

    @Test
    @DisplayName("le résultat rappelle que le moteur local ne dit PAS qui parle")
    void theUnknownSpeakerIsAlwaysNamed() throws IOException {
        JsonNode started = call(TeamsTools.CAPTURE_START, "{\"purpose\":\"self\"}");
        writeCsv(started.path("captureId").asText(), "0,1000,\"Bonjour.\"\n");

        JsonNode stopped = call(TeamsTools.CAPTURE_STOP, "{}");

        assertTrue(gapsOf(stopped).contains("ne dit pas QUI parle"), gapsOf(stopped));
    }

    @Test
    @DisplayName("teams_meeting_moments avec capture_id : vidéo, répliques et ORIGINE DU TEMPS")
    void momentsJoinTheExistingPath() throws IOException {
        JsonNode started = call(TeamsTools.CAPTURE_START, "{\"purpose\":\"self\"}");
        String captureId = started.path("captureId").asText();
        writeCsv(captureId, "0,2400,\"Bonjour à tous.\"\n");
        call(TeamsTools.CAPTURE_STOP, "{}");

        JsonNode moments = call(TeamsTools.MEETING_MOMENTS,
                "{\"capture_id\":\"" + captureId + "\"}");

        // L'origine du temps est celle qu'on a ÉCRITE : c'est le seul cas du volet où l'alignement
        // ne repose sur aucune hypothèse.
        assertTrue(moments.path("timeline").asText().contains(STARTED.toString()),
                moments.path("timeline").asText());
        assertFalse(moments.path("jobId").asText().isBlank());
    }

    @Test
    @DisplayName("une capture ENCORE EN COURS est refusée : on n'extrait pas d'un fichier en écriture")
    void aRunningCaptureIsRefused() throws IOException {
        JsonNode started = call(TeamsTools.CAPTURE_START, "{\"purpose\":\"self\"}");

        JsonNode moments = call(TeamsTools.MEETING_MOMENTS,
                "{\"capture_id\":\"" + started.path("captureId").asText() + "\"}");

        assertEquals(0, moments.path("moments").size());
        assertTrue(moments.path("text").asText().contains("tourne encore"),
                moments.path("text").asText());
        assertTrue(gapsOf(moments).contains("en cours d'écriture"), gapsOf(moments));
    }

    @Test
    @DisplayName("un capture_id inconnu : refus nommé, sans inventer de fichier")
    void anUnknownCaptureIsRefused() throws IOException {
        JsonNode moments = call(TeamsTools.MEETING_MOMENTS, "{\"capture_id\":\"jamais-vu\"}");

        assertEquals(0, moments.path("moments").size());
        assertTrue(moments.path("text").asText().contains("Je ne connais aucun enregistrement"),
                moments.path("text").asText());
    }

    // ------------------------------------------------------------------ montages

    private JsonNode call(String tool, String input) throws IOException {
        return mapper.readTree(tools().execute(tool, mapper.readTree(input)).content());
    }

    private String gapsOf(JsonNode result) {
        return result.path("gaps").toString();
    }

    private TeamsTools tools() {
        return toolsWithoutTranscription().withTranscription(transcription);
    }

    private TeamsTools toolsWithoutTranscription() {
        TeamsSession session = new TeamsSession(9222, TeamsAdapters.current(), said::add,
                (port, adapter, say) -> {
                    throw new BrowserLinkException(BrowserLinkException.BROWSER_NOT_DETECTED,
                            BrowserLaunchAdvice.forSystem(OperatingSystem.LINUX, port));
                });
        return new TeamsTools(session, millis -> { })
                .withCapture(capture)
                .withMoments(new MomentsWorker(new MomentsJobStore(folder),
                        new SceneFrames(toolchain(), processes),
                        MomentUploader.unavailable("aucune remontée dans un test"), directPool()));
    }

    /** Ce que le moteur « aurait écrit » : aucun binaire ne tourne dans un test. */
    private void writeCsv(String captureId, String csv) throws IOException {
        Path dir = folder.capturesDir().resolve(captureId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("transcript.csv"), csv, StandardCharsets.UTF_8);
    }

    private void answerLikeRealBinaries() {
        processes.answering(command -> {
            String binary = Path.of(command.get(0)).getFileName().toString();
            if (command.contains("-filters")) {
                return new ProcessRunner.ProcessResult(0,
                        List.of(" T.. drawtext          V->V       Draw text."), List.of(), false);
            }
            if (command.contains("-vn")) {
                try {
                    Files.writeString(Path.of(command.get(command.size() - 1)), "son de papier");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return FakeProcesses.version(binary);
        });
    }

    private LocalToolchain toolchain() {
        Path ffmpeg = writeBinary("ffmpeg");
        Path engine = writeBinary("whisper-cli");
        Path model = folder.toolsDir().resolve("modele-de-transcription").resolve("ggml-base.bin");
        try {
            Files.createDirectories(model.getParent());
            Files.writeString(model, "modèle de papier");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new LocalToolchain(folder, OperatingSystem.LINUX, processes, (url, into) -> {
            throw new IOException("aucun téléchargement ne doit avoir lieu dans ce test");
        }, said::add, name -> "ffmpeg".equals(name) ? ffmpeg
                : ("whisper-cli".equals(name) ? engine : null));
    }

    private Path writeBinary(String name) {
        Path path = host.resolve(name);
        try {
            Files.writeString(path, "#!/bin/sh\n");
            path.toFile().setExecutable(true);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return path;
    }

    /** Un exécuteur qui exécute sur le fil appelant : on éprouve l'enchaînement, pas les threads. */
    private static ExecutorService directPool() {
        return new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }

            @Override
            public void shutdown() {
                // Rien à arrêter.
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
        };
    }
}
