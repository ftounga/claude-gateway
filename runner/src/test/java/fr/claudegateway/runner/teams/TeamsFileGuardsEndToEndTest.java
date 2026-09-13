package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.OperatingSystem;
import fr.claudegateway.runner.ToolOutcome;

/**
 * F-108 — <b>les gardes, de bout en bout</b>, à travers les outils fichiers (arbitrage PO, point 5).
 *
 * <p>Ce qui est tenu ici n'est pas un comportement de classe mais la promesse faite au PO : hors des
 * domaines Microsoft rien ne part ; sur une page d'identification aucun script ne s'exécute ; aucun
 * secret ne ressort ; les cookies ne sont jamais demandés ; la vue est remise.</p>
 */
class TeamsFileGuardsEndToEndTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path root;

    @TempDir
    Path home;

    private TeamsTools tools(PaperTeams teams) {
        return teams.toolsWithFiles(root, new SyncedLibraries(home, Map.of(), OperatingSystem.LINUX));
    }

    private JsonNode call(TeamsTools tools, String tool, JsonNode input) throws Exception {
        ToolOutcome outcome = tools.execute(tool, input);
        assertTrue(outcome.ok(), outcome.errorMessage());
        return mapper.readTree(outcome.content());
    }

    @Test
    @DisplayName("Emplacement hors liste : AUCUNE navigation, aucun script, refus nommé")
    void off_domain_location_never_moves_the_tab() throws Exception {
        PaperTeams teams = new PaperTeams();

        JsonNode json = call(tools(teams), TeamsTools.LIST_FILES,
                mapper.createObjectNode().put("location", "https://evil.example.com/sites/x/Docs"));

        assertTrue(teams.browser.navigations().isEmpty());
        assertTrue(teams.browser.scripts().isEmpty());
        assertFalse(teams.browser.sentCommands().contains(CdpCommands.PAGE_NAVIGATE));
        assertEquals("LOCATION_UNKNOWN", json.path("gaps").get(0).path("kind").asText());
    }

    @Test
    @DisplayName("Redirection vers la page d'identification : aucun script, SIGNED_OUT, vue remise")
    void sign_in_redirect_runs_nothing() throws Exception {
        PaperTeams teams = new PaperTeams();
        String teamsRoute = teams.browser.route();
        teams.browser.redirectingTo("https://login.microsoftonline.com/common/oauth2/authorize");

        JsonNode json = call(tools(teams), TeamsTools.READ_FILE, mapper.createObjectNode()
                .put("file", TeamsFileToolsTest.PLAN));

        assertTrue(teams.browser.scripts().isEmpty(), "aucun script sur une page d'identification");
        assertEquals("SIGNED_OUT", json.path("gaps").get(0).path("kind").asText(), json.toString());
        assertFalse(json.path("downloaded").asBoolean());
        // La remise est tentée ; la page de papier atterrit toujours sur l'identification, et le
        // runner n'y fait rien : ni clic, ni saisie.
        assertFalse(teams.browser.sentCommands().contains(CdpCommands.INSERT_TEXT));
        assertFalse(teams.browser.sentCommands().contains(CdpCommands.DISPATCH_MOUSE_EVENT));
        assertTrue(teams.browser.navigations().contains(teamsRoute),
                "la vue d'avant est redemandée : " + teams.browser.navigations());
    }

    @Test
    @DisplayName("Page qui renverrait tout (digest, adresse pré-authentifiée) : rien ne ressort")
    void nothing_secret_leaves_even_from_an_unfiltered_page() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.sharePointUnfiltered("lister-les-dossiers",
                        TeamsSamples.read("sharepoint-files-secrets.json"))
                .sharePointUnfiltered("lister-les-fichiers",
                        TeamsSamples.read("sharepoint-files-secrets.json"));

        ToolOutcome outcome = tools(teams).execute(TeamsTools.LIST_FILES, mapper.createObjectNode()
                .put("location", TeamsFileToolsTest.GENERAL));

        assertTrue(outcome.ok());
        assertFalse(outcome.content().contains("SECRET"), outcome.content());
        assertFalse(outcome.content().contains("downloadUrl"), outcome.content());
        assertFalse(outcome.content().contains("tempauth"), outcome.content());
    }

    @Test
    @DisplayName("Cookies et stockage : jamais demandés au navigateur, quel que soit l'outil fichiers")
    void cookies_are_never_asked() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.sharePoint("lister-les-dossiers", 200, TeamsSamples.read("sharepoint-folders.json"))
                .sharePoint("lister-les-fichiers", 200, TeamsSamples.read("sharepoint-files.json"))
                .sharePoint("lire-les-metadonnees-du-fichier", 200,
                        TeamsSamples.read("sharepoint-file.json"))
                .downloading(new byte[48_213]);
        TeamsTools tools = tools(teams);

        call(tools, TeamsTools.LIST_FILES, mapper.createObjectNode()
                .put("location", TeamsFileToolsTest.GENERAL));
        call(tools, TeamsTools.READ_FILE, mapper.createObjectNode()
                .put("file", TeamsFileToolsTest.PLAN));

        for (String command : teams.browser.sentCommands()) {
            assertTrue(CdpCommands.isAllowed(command), command);
            assertFalse(command.toLowerCase().contains("cookie"), command);
            assertFalse(command.toLowerCase().startsWith("storage."), command);
        }
    }
}
