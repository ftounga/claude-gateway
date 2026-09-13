package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.OperatingSystem;
import fr.claudegateway.runner.ToolOutcome;

/**
 * <b>Les trois outils d'enregistrement local</b> (F-91 / SF-91-02), tels que l'agent les voit.
 *
 * <p>Le point de conception vérifié ici est la <b>règle de forme n° 1 du volet</b>, appliquée au seul
 * outil qui <b>crée</b> : un refus reste un <b>succès d'outil</b> porteur du refus et de son remède.
 * Une erreur d'outil ferait dire à l'agent « je n'ai pas réussi », là où il faut dire « confirmez que
 * vous avez prévenu les participants, et voici pourquoi ».</p>
 */
@DisplayName("F-91 / SF-91-02 — les outils d'enregistrement local")
class TeamsCaptureToolsTest {

    @TempDir
    Path host;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> said = new ArrayList<>();
    private TeamsWorkFolder folder;
    private FakeProcesses processes;
    private FakeSessions sessions;
    private LocalCapture capture;

    @BeforeEach
    void setUp() {
        folder = new TeamsWorkFolder(host);
        processes = new FakeProcesses();
        sessions = new FakeSessions();
        said.clear();
        capture = engine();
    }

    @Nested
    @DisplayName("Démarrer")
    class Start {

        @Test
        @DisplayName("nominal : l'identifiant, le filigrane, ET la mention pour le compte rendu")
        void nominal() throws Exception {
            JsonNode json = call(TeamsTools.CAPTURE_START, "{\"purpose\":\"self\"}");

            assertFalse(json.path("captureId").asText().isBlank());
            assertTrue(json.path("running").asBoolean());
            assertEquals("SELF_SCREEN", json.path("purpose").asText());
            assertTrue(json.path("watermark").asText().contains("Enregistrement local"));
            // La trace voyage : le filigrane dans l'image, CETTE mention en tête du compte rendu.
            assertTrue(json.path("recordingNotice").asText().contains("ENREGISTREMENT LOCAL"));
            assertTrue(json.path("video").asText().endsWith("capture.mp4"));
        }

        @Test
        @DisplayName("la phrase dit le plafond ET ce que le produit ne peut pas garantir")
        void textSaysTheLimits() throws Exception {
            JsonNode json = call(TeamsTools.CAPTURE_START, "{\"purpose\":\"meeting\","
                    + "\"participants_informed\":true}");

            String text = json.path("text").asText();
            assertTrue(text.contains("s'arrêtera d'elle-même"), text);
            assertTrue(text.contains("de vive voix"), text);
            assertTrue(text.contains("pas à vous protéger"), text);
        }

        @Test
        @DisplayName("la portée est déclarée UNE fois : ce qui sera enregistré, et où cela ira")
        void scopeIsAnnouncedOnce() throws Exception {
            JsonNode first = call(TeamsTools.CAPTURE_START, "{\"purpose\":\"self\"}");

            assertTrue(first.path("notice").asText().contains("TOUT CE QUI PASSERA À L'ÉCRAN"));
            assertTrue(first.path("notice").asText().contains("RESTENT sur cette machine"));
        }

        @Test
        @DisplayName("réunion sans confirmation : SUCCÈS d'outil portant le refus et son remède")
        void meetingWithoutConfirmationIsASuccessCarryingARefusal() throws Exception {
            ToolOutcome outcome = execute(TeamsTools.CAPTURE_START, "{\"purpose\":\"meeting\"}");

            assertTrue(outcome.ok(), "un refus n'est pas une panne de l'outil");
            JsonNode json = mapper.readTree(outcome.content());
            assertEquals(CaptureRefusedException.NOT_CONFIRMED, json.path("refused").asText());
            assertFalse(json.path("running").asBoolean());
            assertTrue(json.path("text").asText().contains("de vive voix"));
            assertTrue(sessions.calls.isEmpty(), "rien ne doit avoir été lancé");
        }

        @Test
        @DisplayName("« participants_informed » n'est jamais déduit d'un défaut")
        void confirmationIsNeverInferred() throws Exception {
            JsonNode json = call(TeamsTools.CAPTURE_START,
                    "{\"purpose\":\"meeting\",\"participants_informed\":false}");

            assertEquals(CaptureRefusedException.NOT_CONFIRMED, json.path("refused").asText());
        }

        @Test
        @DisplayName("usage absent : refus porté, et l'usage n'est PAS deviné")
        void purposeIsNeverGuessed() throws Exception {
            JsonNode json = call(TeamsTools.CAPTURE_START, "{}");

            assertEquals(CaptureRefusedException.NO_PURPOSE, json.path("refused").asText());
            assertTrue(json.path("text").asText().contains("Je ne le devine pas"));
        }

        @Test
        @DisplayName("un manque est TOUJOURS nommé à côté du résultat, même quand tout va bien")
        void gapsAreAlwaysPresent() throws Exception {
            JsonNode json = call(TeamsTools.CAPTURE_START, "{\"purpose\":\"self\"}");

            assertTrue(json.has("gaps"), "l'enveloppe porte toujours ce qui n'a pas pu être fait");
        }
    }

    @Nested
    @DisplayName("Arrêter et interroger")
    class StopAndStatus {

        @Test
        @DisplayName("arrêter sans identifiant arrête celui qui tourne")
        void stopWithoutId() throws Exception {
            String id = call(TeamsTools.CAPTURE_START, "{\"purpose\":\"self\"}")
                    .path("captureId").asText();

            JsonNode json = call(TeamsTools.CAPTURE_STOP, "{}");

            assertEquals(id, json.path("captureId").asText());
            assertEquals("TERMINEE", json.path("state").asText());
            assertFalse(json.path("running").asBoolean());
            assertTrue(json.path("bytes").asLong() > 0);
        }

        @Test
        @DisplayName("arrêter alors que rien ne tourne : refus porté, sans rien inventer")
        void stopWithNothingRunning() throws Exception {
            JsonNode json = call(TeamsTools.CAPTURE_STOP, "{}");

            assertEquals(CaptureRefusedException.UNKNOWN, json.path("refused").asText());
        }

        @Test
        @DisplayName("l'état sans identifiant rend celui qui tourne")
        void statusWithoutId() throws Exception {
            String id = call(TeamsTools.CAPTURE_START, "{\"purpose\":\"self\"}")
                    .path("captureId").asText();

            JsonNode json = call(TeamsTools.CAPTURE_STATUS, "{}");

            assertEquals(id, json.path("captureId").asText());
            assertTrue(json.path("running").asBoolean());
            assertTrue(json.path("elapsed").asText().matches("\\d\\d:\\d\\d:\\d\\d"));
        }

        @Test
        @DisplayName("aucune capture : zéro ET un manque nommé, jamais un silence rassurant")
        void statusWithNothing() throws Exception {
            JsonNode json = call(TeamsTools.CAPTURE_STATUS, "{}");

            assertEquals(0, json.path("captures").size());
            assertEquals(1, json.path("gaps").size());
            assertTrue(json.path("gaps").get(0).path("detail").asText()
                    .contains("aucun enregistrement local ne tourne"));
            assertTrue(json.path("text").asText().contains("Aucun enregistrement local"));
        }

        @Test
        @DisplayName("l'historique liste les captures précédentes")
        void statusListsHistory() throws Exception {
            call(TeamsTools.CAPTURE_START, "{\"purpose\":\"self\"}");
            call(TeamsTools.CAPTURE_STOP, "{}");

            JsonNode json = call(TeamsTools.CAPTURE_STATUS, "{}");

            assertEquals(1, json.path("captures").size());
            assertEquals("TERMINEE", json.path("captures").get(0).path("state").asText());
        }
    }

    @Nested
    @DisplayName("Ce poste ne sait pas enregistrer")
    class NotAvailable {

        @Test
        @DisplayName("volet désactivé : l'outil le DIT, il ne fait pas semblant")
        void disabledSaysWhy() throws Exception {
            TeamsTools tools = TeamsTools.disabled("Le volet Teams est désactivé (--no-teams).");

            ToolOutcome outcome = tools.execute(TeamsTools.CAPTURE_START,
                    mapper.readTree("{\"purpose\":\"self\"}"));

            assertTrue(outcome.ok());
            JsonNode json = mapper.readTree(outcome.content());
            assertFalse(json.path("running").asBoolean());
            assertTrue(json.path("text").asText().contains("--no-teams"));
            assertTrue(json.path("text").asText().contains("Rien n'a été enregistré"));
        }

        @Test
        @DisplayName("moteur non monté : idem — il le dit, rien n'est enregistré")
        void notMountedSaysWhy() throws Exception {
            TeamsTools tools = toolsWithoutCapture();

            JsonNode json = mapper.readTree(tools.execute(TeamsTools.CAPTURE_STATUS,
                    mapper.readTree("{}")).content());

            assertTrue(json.path("text").asText().contains("pas disponible sur ce poste"));
            assertEquals(1, json.path("gaps").size());
        }
    }

    @Test
    @DisplayName("le catalogue du runner porte les trois outils de capture")
    void catalogCarriesTheThree() {
        assertTrue(TeamsTools.CATALOG.contains(TeamsTools.CAPTURE_START));
        assertTrue(TeamsTools.CATALOG.contains(TeamsTools.CAPTURE_STOP));
        assertTrue(TeamsTools.CATALOG.contains(TeamsTools.CAPTURE_STATUS));
    }

    // ------------------------------------------------------------------ montages

    private JsonNode call(String tool, String input) throws IOException {
        return mapper.readTree(execute(tool, input).content());
    }

    private ToolOutcome execute(String tool, String input) throws IOException {
        return tools().execute(tool, mapper.readTree(input));
    }

    /**
     * Un outillage relié à un Teams de papier — non pas parce que la capture en a besoin (elle n'en
     * a pas : capturer son propre écran n'a rien à voir avec Teams web), mais parce que les outils
     * partagent la même enveloppe.
     */
    private TeamsTools tools() {
        return toolsWithoutCapture().withCapture(capture);
    }

    private TeamsTools toolsWithoutCapture() {
        TeamsSession session = new TeamsSession(9222, TeamsAdapters.current(), said::add,
                (port, adapter, say) -> {
                    throw new BrowserLinkException(BrowserLinkException.BROWSER_NOT_DETECTED,
                            BrowserLaunchAdvice.forSystem(OperatingSystem.LINUX, port));
                });
        return new TeamsTools(session, millis -> { });
    }

    private LocalCapture engine() {
        Path fake = host.resolve("ffmpeg");
        try {
            Files.writeString(fake, "#!/bin/sh\n");
            fake.toFile().setExecutable(true);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        processes.answering(command -> command.contains("-filters")
                ? new ProcessRunner.ProcessResult(0,
                        List.of(" T.. drawtext          V->V       Draw text."), List.of(), false)
                : FakeProcesses.version("ffmpeg"));
        LocalToolchain toolchain = new LocalToolchain(folder, OperatingSystem.LINUX, processes,
                (url, into) -> 0L, said::add, name -> fake);
        return new LocalCapture(folder, OperatingSystem.LINUX, toolchain, processes, sessions,
                new CaptureStore(folder),
                new WatermarkFont(OperatingSystem.LINUX,
                        path -> path.toString().endsWith("DejaVuSans.ttf")),
                millis -> { }, Instant::now, said::add, () -> ":0.0");
    }
}
