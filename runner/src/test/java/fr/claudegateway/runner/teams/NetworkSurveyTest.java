package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-100 / SF-100-00 — <b>le relevé réel</b> : d'où et par quels chemins Teams web reçoit ses réponses,
 * sans jamais lire une réponse, et sans rien écrire qui soit propre au client.
 */
class NetworkSurveyTest {

    @TempDir
    Path output;

    private static NetworkSurvey.Entry entry(NetworkSurvey.Snapshot snapshot, String path) {
        return snapshot.entries().stream().filter(e -> e.path().equals(path)).findFirst().orElseThrow(
                () -> new AssertionError("chemin absent : " + path + " dans "
                        + snapshot.entries().stream().map(NetworkSurvey.Entry::path).toList()));
    }

    // ------------------------------------------------------------------ gabarits

    @Test
    @DisplayName("Le nom du tenant n'est jamais écrit : l'hôte est ramené à son motif")
    void hostIsReducedToItsMotif() {
        assertEquals("*.sharepoint.com", SurveyPaths.hostMotif("https://contoso.sharepoint.com/sites/x"));
        assertEquals("*-my.sharepoint.com", SurveyPaths.hostMotif("https://contoso-my.sharepoint.com/personal/y"));
        assertEquals("teams.microsoft.com", SurveyPaths.hostMotif("https://teams.microsoft.com/api/chatsvc"));
    }

    @Test
    @DisplayName("Le chemin perd sa requête, son ancre, ses identifiants et les noms propres au client")
    void pathIsTemplated() {
        assertEquals("/api/chatsvc/emea/v1/users/{id}/conversations/{id}/messages",
                SurveyPaths.template("https://emea.ng.msg.teams.microsoft.com/api/chatsvc/emea/v1/users/ME/"
                        + "conversations/19:abc123def@thread.v2/messages?startTime=1726253000123&token=SECRET#x"));
        assertEquals("/sites/{id}/_api/v2.1/drives/{id}/items/{id}/media/transcripts/{id}/content",
                SurveyPaths.template("https://contoso.sharepoint.com/sites/ProjetIAM/_api/v2.1/drives/"
                        + "b!AbCdEf0123456789xyz/items/01ABCDEF2345GHIJ/media/transcripts/"
                        + "3f2504e0-4f89-11d3-9a0c-0305e82c3301/content?format=json"));
        assertEquals("/_api/web/GetFileByServerRelativeUrl({id})",
                SurveyPaths.template("https://contoso.sharepoint.com/_api/web/"
                        + "GetFileByServerRelativeUrl('/sites/ProjetIAM/Réunion.mp4')"));
        assertTrue(SurveyPaths.looksLikeId("1726253000123"));
        assertTrue(SurveyPaths.looksLikeId("a1b2c3d4e5f6a7b8c9d0"));
        assertFalse(SurveyPaths.looksLikeId("conversations"));
        assertFalse(SurveyPaths.looksLikeId("v1"));
        assertEquals("/", SurveyPaths.template("https://teams.microsoft.com?x=1"));
    }

    @Test
    @DisplayName("Un chemin trop long est coupé, et la coupe se voit")
    void longPathIsCut() {
        String url = "https://teams.microsoft.com/" + "segment/".repeat(80);
        String template = SurveyPaths.template(url);
        assertTrue(template.endsWith("…"));
        assertEquals(SurveyPaths.MAX_PATH_CHARS + 1, template.length());
    }

    // ------------------------------------------------------------------ origines

    @Test
    @DisplayName("Chaque réponse porte son origine : onglet Teams, cadre intégré, worker, service worker")
    void originIsKnownPerSession() {
        SurveyFakeBrowser tab = new SurveyFakeBrowser();
        NetworkSurvey survey = new NetworkSurvey(Runnable::run);
        survey.watchTeamsTab(tab);
        tab.attach("S-FRAME", "iframe", "https://contoso.sharepoint.com/_layouts/15/stream.aspx");
        tab.attach("S-WORKER", "worker", "https://teams.microsoft.com/worker.js");
        tab.attach("S-SW", "service_worker", "https://teams.microsoft.com/sw.js");

        tab.respond("", "https://teams.microsoft.com/api/csa/api/v1/teams/users/me", "Fetch",
                "application/json; charset=utf-8", 200);
        tab.respond("S-FRAME", "https://contoso.sharepoint.com/_api/v2.1/drives/b!x1y2z3a4b5c6d7e8f9/items/"
                + "01ABCDEF2345GHIJ/media/transcripts", "XHR", "application/json", 200);
        tab.respond("S-WORKER", "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations",
                "Fetch", "application/json", 200);
        tab.respond("S-SW", "https://teams.microsoft.com/api/mt/part/emea-02/beta/me/calendarEvents",
                "Fetch", "application/json", 200);

        NetworkSurvey.Snapshot snapshot = survey.snapshot();
        assertEquals(List.of("TEAMS_TAB"),
                List.copyOf(entry(snapshot, "/api/csa/api/v1/teams/users/{id}").origins()));
        NetworkSurvey.Entry transcript = entry(snapshot, "/_api/v2.1/drives/{id}/items/{id}/media/transcripts");
        assertEquals(List.of("FRAME"), List.copyOf(transcript.origins()));
        assertEquals("*.sharepoint.com", transcript.host());
        assertTrue(transcript.onlyOutsideTeamsTab());
        assertEquals(List.of("WORKER"),
                List.copyOf(entry(snapshot, "/api/chatsvc/emea/v1/users/{id}/conversations").origins()));
        assertEquals(List.of("SERVICE_WORKER"),
                List.copyOf(entry(snapshot, "/api/mt/part/emea-02/beta/me/calendarEvents").origins()));

        // Sur chaque cible retenue : l'écoute du réseau, et la demande que ses propres workers s'annoncent.
        assertTrue(tab.sent().contains("S-FRAME|Network.enable"));
        assertTrue(tab.sent().contains("S-SW|Network.enable"));
    }

    @Test
    @DisplayName("Une cible hors domaines Microsoft n'est jamais écoutée, et ce qu'elle émet n'est pas noté")
    void foreignTargetIsNeverWatched() {
        SurveyFakeBrowser tab = new SurveyFakeBrowser();
        NetworkSurvey survey = new NetworkSurvey(Runnable::run);
        survey.watchTeamsTab(tab);
        tab.attach("S-AD", "iframe", "https://ads.example.com/frame");
        tab.attach("S-BLANK", "iframe", "");
        tab.attach("S-LOGIN", "iframe", "https://login.microsoftonline.com/common/oauth2");
        tab.respond("S-AD", "https://teams.microsoft.com/api/chatsvc/v1/x", "Fetch", "application/json", 200);

        assertFalse(tab.sent().stream().anyMatch(command -> command.startsWith("S-AD|")));
        assertFalse(tab.sent().stream().anyMatch(command -> command.startsWith("S-LOGIN|")));
        NetworkSurvey.Snapshot snapshot = survey.snapshot();
        assertTrue(snapshot.entries().isEmpty());
        assertEquals(3, snapshot.refusedTargets());
    }

    @Test
    @DisplayName("Le relevé n'émet que Network.enable et Target.setAutoAttach : aucun corps n'est jamais demandé")
    void onlyListeningCommandsAreSent() {
        SurveyFakeBrowser tab = new SurveyFakeBrowser();
        NetworkSurvey survey = new NetworkSurvey(Runnable::run);
        survey.watchTeamsTab(tab);
        tab.attach("S1", "iframe", "https://contoso.sharepoint.com/x");
        SurveyFakeBrowser other = new SurveyFakeBrowser();
        assertTrue(survey.watchOtherTab("T2", "https://contoso-my.sharepoint.com/personal/x", other));
        assertFalse(survey.watchOtherTab("T3", "https://www.banque.example/compte", new SurveyFakeBrowser()));
        tab.respond("", "https://teams.microsoft.com/api/chatsvc/v1/users/ME/conversations", "Fetch",
                "application/json", 200);

        Stream.concat(tab.sent().stream(), other.sent().stream()).forEach(command -> {
            String method = command.substring(command.indexOf('|') + 1);
            assertTrue(method.equals(CdpCommands.NETWORK_ENABLE) || method.equals(CdpCommands.SET_AUTO_ATTACH),
                    "commande inattendue : " + command);
        });
    }

    @Test
    @DisplayName("Statiques écartés, hors domaines comptés, étape de première vue retenue")
    void staticOutsideAndSteps() {
        SurveyFakeBrowser tab = new SurveyFakeBrowser();
        NetworkSurvey survey = new NetworkSurvey(Runnable::run);
        survey.watchTeamsTab(tab);
        tab.respond("", "https://teams.microsoft.com/app.js", "Script", "text/javascript", 200);
        tab.respond("", "https://teams.microsoft.com/style.css", "Stylesheet", "text/css", 200);
        tab.respond("", "https://cdn.example.net/font.woff2", "Font", "font/woff2", 200);
        survey.step(2);
        tab.respond("", "https://teams.microsoft.com/api/mt/emea/beta/meetings/19:meeting_abc@thread.v2",
                "Fetch", "application/json", 200);
        survey.step(4);
        tab.respond("", "https://teams.microsoft.com/api/mt/emea/beta/meetings/19:meeting_def@thread.v2",
                "Fetch", "application/json", 200);

        NetworkSurvey.Snapshot snapshot = survey.snapshot();
        assertEquals(2, snapshot.staticResources());
        assertEquals(1, snapshot.outsideMicrosoft());
        NetworkSurvey.Entry meeting = entry(snapshot, "/api/mt/emea/beta/meetings/{id}");
        assertEquals(2, meeting.firstStep());
        assertEquals(2, meeting.count());
        assertEquals("MEETING_DETAILS", meeting.classification());
    }

    // ------------------------------------------------------------------ rapport

    @Test
    @DisplayName("Le rapport ne contient ni requête, ni en-tête, ni tenant, ni identifiant — et il dit les écarts")
    void reportLeaksNothing() {
        SurveyFakeBrowser tab = new SurveyFakeBrowser();
        NetworkSurvey survey = new NetworkSurvey(Runnable::run);
        survey.watchTeamsTab(tab);
        tab.attach("S-FRAME", "iframe", "https://contoso.sharepoint.com/_layouts/15/stream.aspx?id=SECRET");
        tab.respond("", "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations/"
                + "19:clientsecretthread@thread.v2/messages?pageSize=200&syncState=SECRET-ETAT", "Fetch",
                "application/json", 200);
        tab.respond("S-FRAME", "https://contoso.sharepoint.com/sites/ProjetConfidentiel/_api/v2.1/drives/"
                + "b!Zz9Yy8Xx7Ww6Vv5Uu4/items/01QWERTY12345/media/transcripts?access_token=SECRET-JETON",
                "XHR", "application/json", 200);

        SurveyReport report = new SurveyReport(survey.snapshot(), Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                false, "Chrome/140", "v1");
        for (String rendered : List.of(report.markdown(), report.json())) {
            for (String forbidden : List.of("SECRET", "contoso", "ProjetConfidentiel", "clientsecretthread",
                    "pageSize", "access_token", "Bearer", "authtoken", "?")) {
                assertFalse(rendered.contains(forbidden), "fuite « " + forbidden + " » dans :\n" + rendered);
            }
        }
        assertEquals(1, report.gaps().size());
        assertEquals("/sites/{id}/_api/v2.1/drives/{id}/items/{id}/media/transcripts", report.gaps().get(0).path());
        assertEquals(1, report.outsideTeamsTab().size());
        assertTrue(report.markdown().contains("## Écarts avec l'adaptateur"));
        assertTrue(report.markdown().contains("## Vu seulement hors de l'onglet Teams"));
    }

    @Test
    @DisplayName("Un relevé vide le dit ; un relevé interrompu le dit")
    void emptyAndInterruptedReportsSaySo() {
        NetworkSurvey survey = new NetworkSurvey(Runnable::run);
        SurveyReport report = new SurveyReport(survey.snapshot(), Instant.EPOCH, Instant.EPOCH, true, "", "v1");
        assertTrue(report.markdown().contains("Rien observé"));
        assertTrue(report.markdown().contains("interrompu"));
        assertTrue(report.json().contains("\"interrupted\" : true"));
    }

    // ------------------------------------------------------------------ commande

    private static final String LIST = "[{\"id\":\"T1\",\"type\":\"page\",\"url\":\"https://teams.microsoft.com/v2/\","
            + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/page/T1\"},"
            + "{\"id\":\"T2\",\"type\":\"page\",\"url\":\"https://contoso.sharepoint.com/_layouts/15/stream.aspx\","
            + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/page/T2\"},"
            + "{\"id\":\"T3\",\"type\":\"page\",\"url\":\"https://www.banque.example/compte\","
            + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/page/T3\"}]";

    @Test
    @DisplayName("--releve-teams : onglet Teams et onglets Microsoft écoutés, jamais la banque ; rapport écrit")
    void commandWritesTheReport() throws Exception {
        List<String> opened = new ArrayList<>();
        SurveyFakeBrowser teams = new SurveyFakeBrowser();
        List<String> said = new ArrayList<>();
        TeamsSurveyCommand command = new TeamsSurveyCommand(
                url -> url.endsWith("/json/version") ? "{\"Browser\":\"Chrome/140\"}" : LIST,
                ws -> {
                    opened.add(ws);
                    if (ws.endsWith("T1")) {
                        teams.respond("", "https://teams.microsoft.com/api/chatsvc/emea/v1/users/ME/conversations",
                                "Fetch", "application/json", 200);
                        return teams;
                    }
                    return new SurveyFakeBrowser();
                },
                System::currentTimeMillis, said::add, said::add);

        int code = command.execute(new String[] { "--releve-teams", "--sortie", output.toString() }, Map.of(),
                output, new ByteArrayInputStream("1\nfin\n".getBytes(StandardCharsets.UTF_8)));

        assertEquals(0, code);
        assertEquals(List.of("ws://127.0.0.1:9222/devtools/page/T1", "ws://127.0.0.1:9222/devtools/page/T2"),
                opened);
        try (Stream<Path> files = Files.list(output)) {
            List<Path> written = files.toList();
            assertEquals(2, written.size());
        }
        assertFalse(teams.isOpen(), "les sockets sont fermées à la fin");
    }

    @Test
    @DisplayName("--releve-teams : options invalides, navigateur absent, pas d'onglet Teams → code 2")
    void commandRefusals() {
        List<String> said = new ArrayList<>();
        TeamsSurveyCommand noBrowser = new TeamsSurveyCommand(url -> {
            throw new IllegalStateException("injoignable");
        }, ws -> new SurveyFakeBrowser(), System::currentTimeMillis, said::add, said::add);
        String[] base = { "--releve-teams", "--sortie", output.toString() };

        assertEquals(2, noBrowser.execute(base, Map.of(), output, null));
        assertEquals(2, noBrowser.execute(new String[] { "--releve-teams", "--duree", "0" }, Map.of(), output, null));
        assertEquals(2, noBrowser.execute(new String[] { "--releve-teams", "--duree", "x" }, Map.of(), output, null));
        assertEquals(2, noBrowser.execute(new String[] { "--releve-teams", "--sortie", "absent" }, Map.of(), output,
                null));
        assertEquals(2, noBrowser.execute(new String[] { "--releve-teams", "--gateway", "https://x" }, Map.of(),
                output, null));

        TeamsSurveyCommand noTeams = new TeamsSurveyCommand(url -> url.endsWith("/json/version") ? "{}" : "[]",
                ws -> new SurveyFakeBrowser(), System::currentTimeMillis, said::add, said::add);
        assertEquals(2, noTeams.execute(base, Map.of(), output, null));
        assertTrue(TeamsSurveyCommand.requested(base));
        assertFalse(TeamsSurveyCommand.requested(new String[] { "--gateway", "https://x" }));
    }

    @Test
    @DisplayName("Liaison perdue : le rapport partiel est écrit et dit « interrompu »")
    void lostLinkWritesPartialReport() throws Exception {
        SurveyFakeBrowser teams = new SurveyFakeBrowser();
        teams.lose();
        TeamsSurveyCommand command = new TeamsSurveyCommand(
                url -> url.endsWith("/json/version") ? "{}" : LIST, ws -> ws.endsWith("T1") ? teams
                        : new SurveyFakeBrowser(),
                System::currentTimeMillis, line -> { }, line -> { });

        assertEquals(0, command.execute(new String[] { "--releve-teams" }, Map.of(), output, null));
        try (Stream<Path> files = Files.list(output)) {
            Path markdown = files.filter(path -> path.toString().endsWith(".md")).findFirst().orElseThrow();
            assertTrue(Files.readString(markdown).contains("interrompu"));
        }
    }

    // ------------------------------------------------------------------ socket

    @Test
    @DisplayName("La socket transmet la session d'un événement ; sans session, l'onglet ; liaison sans session refuse")
    void socketForwardsSession() {
        WebSocketCdpConnection connection = new WebSocketCdpConnection();
        List<String> seen = new ArrayList<>();
        connection.onSessionEvent("Network.responseReceived", (session, params) ->
                seen.add(session + "|" + params.path("requestId").asText()));
        connection.onEvent("Network.responseReceived", params -> seen.add("onglet|" + params.path("requestId").asText()));
        connection.dispatch("{\"method\":\"Network.responseReceived\",\"sessionId\":\"S9\",\"params\":{\"requestId\":\"a\"}}");
        connection.dispatch("{\"method\":\"Network.responseReceived\",\"params\":{\"requestId\":\"b\"}}");
        assertEquals(List.of("S9|a", "onglet|a", "|b", "onglet|b"), seen);

        FakeCdpConnection legacy = new FakeCdpConnection();
        org.junit.jupiter.api.Assertions.assertThrows(BrowserLinkException.class,
                () -> legacy.send("S1", CdpCommands.NETWORK_ENABLE, null));
        org.junit.jupiter.api.Assertions.assertThrows(BrowserLinkException.class,
                () -> legacy.send("S1", "Network.getCookies", null));
    }
}
