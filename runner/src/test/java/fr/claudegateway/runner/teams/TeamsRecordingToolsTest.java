package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.OperatingSystem;
import fr.claudegateway.runner.ToolContext;
import fr.claudegateway.runner.ToolOutcome;

/**
 * F-108 / SF-108-05 — <b>les enregistrements Teams</b>, vus de l'extérieur : Chrome télécharge, la
 * transcription vient de la meilleure source, et la chaîne F-90 prend le fichier obtenu.
 */
class TeamsRecordingToolsTest {

    static final String MEETING = "MTG-FABRIQUE-0001";
    static final String MEETING_THREAD_URL = "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/"
            + "conversations/19:meeting_fabrique@thread.v2/messages";

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path root;

    @TempDir
    Path home;

    private PaperTeams teamsWithRecording() {
        PaperTeams teams = new PaperTeams()
                .already("m1", TeamsSamples.MEETINGS_URL, "meetings.json")
                .already("r1", MEETING_THREAD_URL, "conversation-messages-recording.json");
        teams.browser.sharePoint("lire-les-metadonnees-du-fichier", 200,
                        TeamsSamples.read("sharepoint-recording-file.json"))
                .sharePoint("lister-les-dossiers", 200, mapper.createObjectNode().set("value",
                        mapper.createArrayNode()))
                .sharePoint("lister-les-fichiers", 200, TeamsSamples.read("sharepoint-recordings-files.json"));
        return teams;
    }

    private TeamsTools tools(PaperTeams teams) {
        return teams.toolsWithFiles(root, new SyncedLibraries(home, Map.of(), OperatingSystem.LINUX));
    }

    private JsonNode call(TeamsTools tools, String tool, ObjectNode input) throws Exception {
        ToolOutcome outcome = tools.execute(tool, input, ToolContext.none());
        assertTrue(outcome.ok(), outcome.errorMessage());
        return mapper.readTree(outcome.content());
    }

    private ObjectNode meeting() {
        return mapper.createObjectNode().put("meeting_id", MEETING);
    }

    @Test
    @DisplayName("Nominal : adresse observée, Chrome télécharge vidéo et .vtt, transcription lue")
    void the_recording_is_downloaded_by_chrome_and_its_vtt_is_read() throws Exception {
        PaperTeams teams = teamsWithRecording();
        teams.browser.downloadingFor("reunion.mp4", new byte[4096])
                .downloadingFor("reunion.vtt", VttTranscriptTest.sample().getBytes(StandardCharsets.UTF_8));
        String teamsRoute = teams.browser.route();

        ToolOutcome outcome = tools(teams).execute(TeamsTools.MEETING_RECORDING, meeting());
        JsonNode json = mapper.readTree(outcome.content());

        assertTrue(json.path("downloaded").asBoolean(), json.toString());
        Path video = Path.of(json.path("video").asText());
        assertEquals(4096L, Files.size(video));
        assertTrue(video.startsWith(new TeamsWorkFolder(root).downloadsDir()), video.toString());
        assertEquals("VTT_FILE", json.path("transcript").path("source").asText());
        assertEquals(2, json.path("transcript").path("cues").asInt());
        assertEquals("BROWSER", json.path("route").asText());
        assertEquals(SharePointFiles.PROVENANCE, json.path("provenance").asText());
        // Chrome a téléchargé par des adresses construites et non signées ; la vue est remise.
        assertEquals(2, teams.browser.navigations().stream()
                .filter(url -> url.contains("/_layouts/15/download.aspx?SourceUrl=")).count());
        assertTrue(teams.browser.navigations().stream().noneMatch(url -> url.contains("tempauth")));
        assertFalse(outcome.content().contains("SECRET"), outcome.content());
        assertEquals("default", teams.browser.downloadBehaviors()
                .get(teams.browser.downloadBehaviors().size() - 1));
        assertEquals(teamsRoute, teams.browser.route());
        assertTrue(json.path("text").asText().contains(TeamsTools.MEETING_MOMENTS));
        // Le fichier-témoin ne porte rien de secret.
        Path sidecar = new TeamsWorkFolder(root).downloadsDir().resolve("rec-MTG-FABRIQUE-0001.json");
        String witness = Files.readString(sidecar);
        for (String forbidden : List.of("SECRET", "tempauth", "Cookie", "Digest", "Bearer")) {
            assertFalse(witness.contains(forbidden), forbidden + " dans " + witness);
        }
    }

    @Test
    @DisplayName("Long : la main est rendue en cours ; le rappel suit SANS aucun geste d'action")
    void a_long_download_is_followed_without_gestures() throws Exception {
        PaperTeams teams = teamsWithRecording();
        teams.browser.downloadingFor("reunion.mp4", new byte[1000]);
        TeamsTools tools = tools(teams);

        JsonNode first = call(tools, TeamsTools.MEETING_RECORDING, meeting());
        assertTrue(first.path("inProgress").asBoolean(), first.toString());
        assertFalse(first.path("downloaded").asBoolean());

        // Chrome finit son travail.
        Path partial = teams.browser.downloads().stream()
                .filter(path -> path.toString().contains("rec-MTG-FABRIQUE-0001")
                        && !path.toString().contains("transcript"))
                .findFirst().orElseThrow();
        Files.write(partial, new byte[3096], java.nio.file.StandardOpenOption.APPEND);
        int navigations = teams.browser.navigations().size();
        int scripts = teams.browser.scripts().size();
        int behaviors = teams.browser.downloadBehaviors().size();

        JsonNode second = call(tools, TeamsTools.MEETING_RECORDING, meeting());

        assertTrue(second.path("downloaded").asBoolean(), second.toString());
        assertEquals(navigations, teams.browser.navigations().size(), "aucune navigation au rappel");
        assertEquals(scripts, teams.browser.scripts().size(), "aucun script au rappel");
        assertEquals(behaviors, teams.browser.downloadBehaviors().size());
    }

    @Test
    @DisplayName("Téléchargement bloqué par l'organisateur ou le tenant : manque nommé, jamais un silence")
    void a_blocked_download_is_named() throws Exception {
        PaperTeams teams = teamsWithRecording();

        JsonNode json = call(tools(teams), TeamsTools.MEETING_RECORDING, meeting());

        assertFalse(json.path("downloaded").asBoolean());
        assertTrue(json.path("gaps").toString().contains("DOWNLOAD_BLOCKED"), json.toString());
        assertTrue(json.path("text").asText().contains("organisateur"), json.path("text").asText());
    }

    @Test
    @DisplayName("Adresse non observée : manque nommé, remède, et AUCUN geste")
    void an_unknown_location_moves_nothing() throws Exception {
        PaperTeams teams = new PaperTeams().already("m1", TeamsSamples.MEETINGS_URL, "meetings.json");

        JsonNode json = call(tools(teams), TeamsTools.MEETING_RECORDING, meeting());

        assertFalse(json.path("downloaded").asBoolean());
        assertTrue(json.path("gaps").toString().contains("LOCATION_UNKNOWN"), json.toString());
        assertTrue(json.path("text").asText().contains("ne sais pas encore où"));
        assertTrue(teams.browser.navigations().isEmpty());
    }

    @Test
    @DisplayName("Ni Teams ni .vtt : la transcription LOCALE démarre sur le fichier téléchargé")
    void local_transcription_starts_when_nothing_else() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("m1", TeamsSamples.MEETINGS_URL, "meetings.json")
                .already("r1", MEETING_THREAD_URL, "conversation-messages-recording.json");
        ObjectNode onlyVideo = mapper.createObjectNode();
        onlyVideo.putArray("value").add(TeamsSamples.read("sharepoint-recording-file.json"));
        teams.browser.sharePoint("lire-les-metadonnees-du-fichier", 200,
                        TeamsSamples.read("sharepoint-recording-file.json"))
                .sharePoint("lister-les-dossiers", 200, mapper.createObjectNode().set("value",
                        mapper.createArrayNode()))
                .sharePoint("lister-les-fichiers", 200, onlyVideo)
                .downloadingFor("reunion.mp4", new byte[4096]);
        List<Runnable> submitted = new java.util.ArrayList<>();
        TeamsTools tools = tools(teams).withTranscription(idleWorker(submitted));

        JsonNode json = call(tools, TeamsTools.MEETING_RECORDING, meeting());

        assertTrue(json.path("downloaded").asBoolean(), json.toString());
        assertEquals("LOCAL", json.path("transcript").path("source").asText(), json.toString());
        assertFalse(json.path("transcript").path("done").asBoolean());
        assertEquals(1, submitted.size(), "le travail long est confié à l'exécuteur, pas fait ici");
        assertTrue(json.path("text").asText().contains("SUR CETTE MACHINE"));
    }

    @Test
    @DisplayName("Chaîne F-90 : meeting_id seul refuse tant que le téléchargement n'est pas fini")
    void moments_wait_for_the_download() throws Exception {
        PaperTeams teams = teamsWithRecording();
        teams.browser.downloadingFor("reunion.mp4", new byte[10]);
        TeamsTools tools = tools(teams).withMoments(momentsWorker());
        call(tools, TeamsTools.MEETING_RECORDING, meeting());

        JsonNode json = call(tools, TeamsTools.MEETING_MOMENTS, meeting());

        assertEquals(0, json.path("moments").size());
        assertTrue(json.path("text").asText().contains("n'est pas terminé"), json.path("text").asText());
        assertFalse(json.has("jobId"));
    }

    @Test
    @DisplayName("Chaîne F-90 : meeting_id seul démarre le travail sur l'enregistrement téléchargé")
    void moments_start_on_the_downloaded_recording() throws Exception {
        PaperTeams teams = teamsWithRecording();
        teams.browser.downloadingFor("reunion.mp4", new byte[4096])
                .downloadingFor("reunion.vtt", VttTranscriptTest.sample().getBytes(StandardCharsets.UTF_8));
        TeamsTools tools = tools(teams).withMoments(momentsWorker());
        JsonNode recording = call(tools, TeamsTools.MEETING_RECORDING, meeting());

        JsonNode json = call(tools, TeamsTools.MEETING_MOMENTS, meeting());

        // Le travail porte bien sur la vidéo rapatriée : son identifiant est tiré de ce chemin.
        assertEquals(MomentsJobStore.idFor(Path.of(recording.path("video").asText())
                .toAbsolutePath().normalize()), json.path("jobId").asText(), json.toString());
    }

    // ------------------------------------------------------------------ montage

    private static TranscriptionWorker idleWorker(List<Runnable> submitted) {
        java.util.concurrent.ExecutorService pool = new java.util.concurrent.AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                submitted.add(command);
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
            public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
                return true;
            }
        };
        return new TranscriptionWorker(null, null, null, pool);
    }

    private MomentsWorker momentsWorker() throws Exception {
        TeamsWorkFolder folder = new TeamsWorkFolder(home);
        Path binary = home.resolve("ffmpeg");
        if (!Files.exists(binary)) {
            Files.createFile(binary);
            binary.toFile().setExecutable(true);
        }
        LocalToolchain toolchain = new LocalToolchain(folder, OperatingSystem.LINUX,
                (command, dir, timeout) -> FakeProcesses.version("ffmpeg"),
                (url, into) -> 0L, message -> { }, name -> binary);
        SceneFrames frames = new SceneFrames(toolchain, new FakeProcesses().succeedingWith(List.of()));
        return new MomentsWorker(new MomentsJobStore(folder), frames, (workspaceId, image) -> "img-1");
    }
}
