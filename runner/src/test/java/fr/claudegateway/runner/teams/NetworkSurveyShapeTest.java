package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-89 / SF-89-12 — <b>le relevé de forme</b> : opt-in, il lit le corps des seules réponses classées et
 * n'en écrit que le squelette (noms + types), jamais une valeur. Sans le drapeau, aucun corps n'est
 * demandé.
 */
class NetworkSurveyShapeTest {

    @TempDir
    Path output;

    private static final String CONV_LIST_URL =
            "https://teams.microsoft.com/api/csa/emea/api/v1/teams/users/me/conversations";
    private static final String COLLAB_URL =
            "https://teams.microsoft.com/api/mcps/eu/collab/readcollabobject/V2/x/y/z";
    private static final String PROFILE_URL =
            "https://teams.microsoft.com/api/mt/emea/beta/users/8:orgid:me/profile";

    private static final String CONV_LIST_BODY = "{\"conversations\":[{"
            + "\"id\":\"19:SECRET-THREAD-ID@thread.v2\","
            + "\"title\":\"Projet-Confidentiel-CAGIP\","
            + "\"members\":[{\"displayName\":\"Jean Dupont\",\"email\":\"jean.dupont@cagip.fr\"}]}],"
            + "\"@odata.count\":1}";
    private static final String COLLAB_BODY = "{\"collabObject\":{\"threadId\":\"19:xyz\","
            + "\"summary\":\"NOTES-SECRETES-COLLAB\"}}";
    private static final String PROFILE_BODY =
            "{\"displayName\":\"Jean Dupont\",\"email\":\"jean.dupont@cagip.fr\"}";

    private static final List<String> FORBIDDEN = List.of("SECRET-THREAD-ID", "Projet-Confidentiel-CAGIP",
            "Jean", "Dupont", "jean.dupont", "@cagip", "NOTES-SECRETES", "SECRET-COOKIE", "SECRET-JETON",
            "Bearer", "Set-Cookie", "authtoken");

    private static NetworkSurvey.Shape shape(NetworkSurvey.Snapshot snap, TeamsPayloadKind kind) {
        return snap.shapes().stream().filter(s -> s.kind() == kind).findFirst().orElse(null);
    }

    @Test
    @DisplayName("Sans --forme : aucun corps n'est jamais demandé (Network.getResponseBody non émis)")
    void withoutShapeModeNoBodyIsEverFetched() {
        SurveyFakeBrowser tab = new SurveyFakeBrowser();
        NetworkSurvey survey = new NetworkSurvey(Runnable::run); // mode normal
        survey.watchTeamsTab(tab);
        tab.respondWithBody("", CONV_LIST_URL, "application/json", 200, CONV_LIST_BODY);
        survey.captureReadyShapes(); // no-op hors mode forme

        assertFalse(tab.sent().stream().anyMatch(c -> c.endsWith(CdpCommands.GET_RESPONSE_BODY)),
                "aucun getResponseBody ne doit être émis hors mode forme : " + tab.sent());
        NetworkSurvey.Snapshot snap = survey.snapshot();
        assertFalse(snap.shapeMode());
        assertTrue(snap.shapes().isEmpty());
        assertEquals(0, snap.shapesRead());
    }

    @Test
    @DisplayName("Avec --forme : seules les réponses classées voient leur forme lue ; PROFILE non ; aucune valeur ne fuit")
    void shapeModeCapturesClassifiedOnlyAndLeaksNoValue() {
        SurveyFakeBrowser tab = new SurveyFakeBrowser();
        NetworkSurvey survey = new NetworkSurvey(Runnable::run, true);
        survey.watchTeamsTab(tab);
        tab.attach("S-WORKER", "worker", "https://teams.microsoft.com/worker.js");

        tab.respondWithBody("S-WORKER", CONV_LIST_URL, "application/json", 200, CONV_LIST_BODY);
        tab.respondWithBody("", COLLAB_URL, "application/json", 200, COLLAB_BODY);
        tab.respondWithBody("", PROFILE_URL, "application/json", 200, PROFILE_BODY); // hors des 5 genres
        survey.captureReadyShapes();

        NetworkSurvey.Snapshot snap = survey.snapshot();
        assertTrue(snap.shapeMode());
        // CA4 : PROFILE n'est jamais mis en file → aucun getResponseBody, jamais de squelette.
        assertEquals(null, shape(snap, TeamsPayloadKind.PROFILE));
        assertEquals(2, tab.sent().stream().filter(c -> c.endsWith(CdpCommands.GET_RESPONSE_BODY)).count(),
                "seules les 2 réponses classées sont lues : " + tab.sent());
        assertEquals(2, snap.shapesRead());

        // CA2 : la liste des conversations livre son squelette, avec origine et version d'API.
        NetworkSurvey.Shape list = shape(snap, TeamsPayloadKind.CONVERSATION_LIST);
        assertTrue(list.skeleton().contains("conversations: array<object>"), list.skeleton());
        assertTrue(list.skeleton().contains("id: string"), list.skeleton());
        assertTrue(list.skeleton().contains("members: array<object>"), list.skeleton());
        assertTrue(list.skeleton().contains("@odata.count: number"), list.skeleton());
        assertTrue(list.origins().contains("WORKER"), list.origins().toString());
        assertTrue(list.apiVersions().contains("v1"), list.apiVersions().toString());

        // CA9 : MEETING_COLLAB_OBJECT (nommé, jamais lu côté outils) voit SA forme relevée ici.
        NetworkSurvey.Shape collab = shape(snap, TeamsPayloadKind.MEETING_COLLAB_OBJECT);
        assertTrue(collab.skeleton().contains("collabObject:"), collab.skeleton());

        // CA3 (VIE PRIVÉE) : ni le markdown ni le JSON ne portent une seule valeur du corps.
        SurveyReport report = new SurveyReport(snap, Instant.EPOCH, Instant.EPOCH.plusSeconds(60), false,
                "Chrome/151", "v1");
        for (String rendered : List.of(report.markdown(), report.json())) {
            for (String secret : FORBIDDEN) {
                assertFalse(rendered.contains(secret), "fuite « " + secret + " » dans :\n" + rendered);
            }
        }
        // CA8 : la section existe, groupée par genre, chemin assaini (pas de « me » ni « thread.v2 »).
        String md = report.markdown();
        assertTrue(md.contains("## Squelette des réponses classées"), md);
        assertTrue(md.contains("### CONVERSATION_LIST"), md);
        assertTrue(md.contains("### MEETING_COLLAB_OBJECT"), md);
        assertTrue(md.contains("**Mode forme : ACTIF**"), md);
        assertFalse(md.contains("users/me"), "le chemin est assaini (me → {id}) : " + md);
    }

    @Test
    @DisplayName("Une réponse refusée (401/403) n'est jamais lue, même en mode forme")
    void deniedResponsesAreNeverRead() {
        SurveyFakeBrowser tab = new SurveyFakeBrowser();
        NetworkSurvey survey = new NetworkSurvey(Runnable::run, true);
        survey.watchTeamsTab(tab);
        tab.respondWithBody("", CONV_LIST_URL, "application/json", 403, CONV_LIST_BODY);
        survey.captureReadyShapes();

        assertFalse(tab.sent().stream().anyMatch(c -> c.endsWith(CdpCommands.GET_RESPONSE_BODY)),
                "une réponse refusée n'a pas de corps utile : " + tab.sent());
        assertTrue(survey.snapshot().shapes().isEmpty());
    }

    // ------------------------------------------------------------------ commande

    private static final String LIST = "[{\"id\":\"T1\",\"type\":\"page\","
            + "\"url\":\"https://teams.microsoft.com/v2/\","
            + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/page/T1\"}]";

    @Test
    @DisplayName("--releve-teams --forme : l'annonce et le rapport disent le mode forme (consentement, traçabilité)")
    void commandAnnouncesAndReportsShapeMode() throws Exception {
        SurveyFakeBrowser teams = new SurveyFakeBrowser();
        List<String> said = new java.util.ArrayList<>();
        TeamsSurveyCommand command = new TeamsSurveyCommand(
                url -> url.endsWith("/json/version") ? "{\"Browser\":\"Chrome/151\"}" : LIST,
                ws -> ws.endsWith("T1") ? teams : new SurveyFakeBrowser(),
                System::currentTimeMillis, said::add, said::add);

        int code = command.execute(new String[] { "--releve-teams", "--forme", "--sortie", output.toString() },
                Map.of(), output, new ByteArrayInputStream("1\nfin\n".getBytes(StandardCharsets.UTF_8)));

        assertEquals(0, code);
        assertTrue(said.stream().anyMatch(l -> l.contains("MODE FORME ACTIF")), "annonce : " + said);
        try (Stream<Path> files = Files.list(output)) {
            Path markdown = files.filter(p -> p.toString().endsWith(".md")).findFirst().orElseThrow();
            String md = Files.readString(markdown);
            assertTrue(md.contains("Mode forme : ACTIF"), md);
        }
    }

    @Test
    @DisplayName("--releve-teams sans --forme : la commande n'émet jamais getResponseBody")
    void commandWithoutShapeModeFetchesNoBody() throws Exception {
        SurveyFakeBrowser teams = new SurveyFakeBrowser();
        TeamsSurveyCommand command = new TeamsSurveyCommand(
                url -> url.endsWith("/json/version") ? "{}" : LIST,
                ws -> ws.endsWith("T1") ? teams : new SurveyFakeBrowser(),
                System::currentTimeMillis, line -> { }, line -> { });

        assertEquals(0, command.execute(new String[] { "--releve-teams", "--sortie", output.toString() },
                Map.of(), output, new ByteArrayInputStream("fin\n".getBytes(StandardCharsets.UTF_8))));
        assertFalse(teams.sent().stream().anyMatch(c -> c.endsWith(CdpCommands.GET_RESPONSE_BODY)),
                teams.sent().toString());
    }
}
