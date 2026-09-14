package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.OperatingSystem;
import fr.claudegateway.runner.ToolOutcome;

/**
 * F-108 / SF-108-03 — <b>lire les fichiers</b>, vus de l'extérieur : ce que l'agent reçoit.
 */
class TeamsFileToolsTest {

    static final String GENERAL =
            "https://contoso.sharepoint.com/sites/ProjetIAM/Shared%20Documents/General";
    static final String PLAN = GENERAL + "/plan-migration.docx";

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path root;

    @TempDir
    Path home;

    private SyncedLibraries nothingSynced() {
        return new SyncedLibraries(home, Map.of(), OperatingSystem.LINUX);
    }

    private JsonNode call(TeamsTools tools, String tool, ObjectNode input) throws Exception {
        ToolOutcome outcome = tools.execute(tool, input);
        assertTrue(outcome.ok(), outcome.errorMessage());
        return mapper.readTree(outcome.content());
    }

    @Test
    @DisplayName("Liste par le navigateur : dossiers et fichiers, gestes tracés, vue remise, provenance")
    void listing_through_the_browser() throws Exception {
        PaperTeams teams = new PaperTeams();
        String teamsRoute = teams.browser.route();
        teams.browser.sharePoint("lister-les-dossiers", 200, TeamsSamples.read("sharepoint-folders.json"))
                .sharePoint("lister-les-fichiers", 200, TeamsSamples.read("sharepoint-files.json"));

        JsonNode json = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.LIST_FILES,
                mapper.createObjectNode().put("location", GENERAL));

        assertEquals("BROWSER", json.path("route").asText());
        assertEquals(4, json.path("items").size());
        assertEquals("plan-migration.docx", json.path("items").get(2).path("name").asText());
        assertEquals(48_213, json.path("items").get(2).path("size").asLong());
        assertEquals("ProjetIAM › Shared Documents › General",
                json.path("location").path("label").asText());
        assertEquals(SharePointFiles.PROVENANCE, json.path("provenance").asText());
        assertTrue(json.path("text").asText().contains("à confirmer sur poste réel"));
        // L'onglet est allé sur le site, puis a été REMIS où il était (§4.7) — et c'est dit.
        assertEquals("https://contoso.sharepoint.com/sites/ProjetIAM/_api/web/title",
                teams.browser.navigations().get(0));
        assertEquals(teamsRoute, teams.browser.route());
        assertTrue(json.path("viewport").asText().contains("remis"), json.path("viewport").asText());
        assertTrue(json.path("gestures").size() >= 3, json.path("gestures").toString());
        assertEquals(0, json.path("gaps").size(), json.path("gaps").toString());
    }

    @Test
    @DisplayName("Réponse réelle non conforme : zéro élément et SHAPE_MISMATCH, jamais à moitié")
    void a_real_response_that_differs_fails_loudly() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.sharePoint("lister-les-dossiers", 200, TeamsSamples.read("sharepoint-folders.json"))
                .sharePoint("lister-les-fichiers", 200, TeamsSamples.read("sharepoint-files-partial.json"));

        JsonNode json = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.LIST_FILES,
                mapper.createObjectNode().put("location", GENERAL));

        assertEquals(0, json.path("items").size());
        assertEquals("SHAPE_MISMATCH", json.path("gaps").get(0).path("kind").asText());
        assertTrue(json.path("text").asText().contains("Ce qui n'a pas pu être fait"));
    }

    @Test
    @DisplayName("403 : manque ACCESS_DENIED, rien de rendu")
    void access_denied_is_named() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.sharePoint("lister-les-dossiers", 403, TeamsSamples.read("sharepoint-error-403.json"));

        JsonNode json = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.LIST_FILES,
                mapper.createObjectNode().put("location", GENERAL));

        assertEquals("ACCESS_DENIED", json.path("gaps").get(0).path("kind").asText());
        assertEquals(0, json.path("items").size());
    }

    @Test
    @DisplayName("Dossier synchronisé : lu sur le disque, AUCUNE commande envoyée au navigateur")
    void a_synced_folder_needs_no_gesture() throws Exception {
        Files.createDirectories(home.resolve("OneDrive - Contoso"));
        Path general = Files.createDirectories(
                home.resolve("Contoso").resolve("ProjetIAM - Documents").resolve("General"));
        Files.writeString(general.resolve("plan-migration.docx"), "contenu");
        Files.createDirectories(general.resolve("Livrables"));
        PaperTeams teams = new PaperTeams();
        int before = teams.browser.sentCommands().size();

        JsonNode json = call(teams.toolsWithFiles(root,
                new SyncedLibraries(home, Map.of(), OperatingSystem.WINDOWS)),
                TeamsTools.LIST_FILES, mapper.createObjectNode().put("location", GENERAL));

        assertEquals("SYNCED_FOLDER", json.path("route").asText());
        assertEquals(2, json.path("items").size());
        assertEquals(before, teams.browser.sentCommands().size(),
                "aucune commande CDP : " + teams.browser.sentCommands());
        assertTrue(json.path("text").asText().contains("aucun geste"));
    }

    @Test
    @DisplayName("Lecture : Chrome télécharge dans le dossier fixe du volet, fichier renommé et rendu")
    void reading_a_file_downloads_it_through_chrome() throws Exception {
        PaperTeams teams = new PaperTeams();
        byte[] content = new byte[48_213];
        teams.browser.sharePoint("lire-les-metadonnees-du-fichier", 200,
                TeamsSamples.read("sharepoint-file.json")).downloading(content);

        JsonNode json = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.READ_FILE,
                mapper.createObjectNode().put("file", PLAN));

        assertTrue(json.path("downloaded").asBoolean(), json.toString());
        Path local = Path.of(json.path("localPath").asText());
        assertEquals("plan-migration.docx", local.getFileName().toString());
        assertEquals(48_213L, Files.size(local));
        assertTrue(local.startsWith(new TeamsWorkFolder(root).downloadsDir()), local.toString());
        // Adresse de téléchargement construite, non signée ; téléchargements rendus par défaut.
        assertTrue(teams.browser.navigations().stream()
                .anyMatch(url -> url.contains("/_layouts/15/download.aspx?SourceUrl=")));
        assertTrue(teams.browser.navigations().stream().noneMatch(url -> url.contains("tempauth")));
        assertEquals("default", teams.browser.downloadBehaviors()
                .get(teams.browser.downloadBehaviors().size() - 1));
        assertEquals("BROWSER", json.path("route").asText());
    }

    @Test
    @DisplayName("Page HTML reçue au lieu du document : DOWNLOAD_BLOCKED, fichier supprimé")
    void an_html_page_is_not_the_document() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.sharePoint("lire-les-metadonnees-du-fichier", 200,
                TeamsSamples.read("sharepoint-file.json"))
                .downloading("<!DOCTYPE html><html><body>Accès refusé</body></html>"
                        .getBytes(StandardCharsets.UTF_8));

        JsonNode json = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.READ_FILE,
                mapper.createObjectNode().put("file", PLAN));

        assertFalse(json.path("downloaded").asBoolean());
        assertEquals("DOWNLOAD_BLOCKED", json.path("gaps").get(0).path("kind").asText());
        assertTrue(teams.browser.downloads().stream().noneMatch(Files::exists));
        assertTrue(json.path("text").asText().contains("n'a PAS été rapatrié"));
    }

    @Test
    @DisplayName("Téléchargement qui ne démarre pas : DOWNLOAD_BLOCKED nommé, jamais un silence")
    void a_download_that_never_starts_is_named() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.sharePoint("lire-les-metadonnees-du-fichier", 200,
                TeamsSamples.read("sharepoint-file.json"));

        JsonNode json = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.READ_FILE,
                mapper.createObjectNode().put("file", PLAN));

        assertFalse(json.path("downloaded").asBoolean());
        assertEquals("DOWNLOAD_BLOCKED", json.path("gaps").get(0).path("kind").asText());
    }

    @Test
    @DisplayName("Conversation : le dossier des pièces jointes observées est listé")
    void a_conversation_lists_the_folder_of_its_attachments() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages-files.json");
        teams.browser.sharePoint("lister-les-dossiers", 200, TeamsSamples.read("sharepoint-folders.json"))
                .sharePoint("lister-les-fichiers", 200, TeamsSamples.read("sharepoint-files.json"));

        JsonNode json = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.LIST_FILES,
                mapper.createObjectNode().put("conversation_id", PaperTeams.THREAD));

        assertEquals("/sites/ProjetIAM/Shared Documents/General",
                json.path("location").path("serverRelativeUrl").asText(), json.toString());
        assertEquals(4, json.path("items").size());
    }

    @Test
    @DisplayName("Sans emplacement : les emplacements connus, sans aucun geste")
    void without_location_the_known_places_are_given() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages-files.json");

        JsonNode json = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.LIST_FILES,
                mapper.createObjectNode());

        assertEquals(1, json.path("locations").size(), json.toString());
        assertTrue(teams.browser.navigations().isEmpty());
    }

    @Test
    @DisplayName("OneDrive : hôte observé, adresse personnelle lue depuis la page, puis listé")
    void onedrive_is_found_then_listed() throws Exception {
        PaperTeams teams = new PaperTeams()
                .already("r1", PaperTeams.MESSAGES_URL, "conversation-messages-files.json");
        teams.browser.sharePoint("trouver-le-onedrive-personnel", 200,
                        TeamsSamples.read("onedrive-my-properties.json"))
                .sharePoint("lister-les-dossiers", 200, TeamsSamples.read("sharepoint-folders.json"))
                .sharePoint("lister-les-fichiers", 200, TeamsSamples.read("sharepoint-files.json"));

        JsonNode json = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.LIST_FILES,
                mapper.createObjectNode().put("onedrive", true));

        assertEquals("OneDrive › Documents", json.path("location").path("label").asText(),
                json.toString());
        assertTrue(teams.browser.navigations().get(0).startsWith("https://contoso-my.sharepoint.com/"));
    }

    @Test
    @DisplayName("Diagnostic : teams_status relève les chemins SharePoint observés, sans requête")
    void status_reports_observed_file_paths() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.emitResponse("sp1",
                "https://contoso.sharepoint.com/sites/ProjetIAM/_api/web/lists?$select=Title&tempauth=SECRET",
                "{}");

        JsonNode json = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.STATUS,
                mapper.createObjectNode());

        JsonNode paths = json.path("diagnostic").path("observedFilePaths");
        assertEquals(1, paths.size(), json.toString());
        // Gabarisé comme le relevé réel : ni tenant, ni site, ni requête.
        assertEquals("*.sharepoint.com/sites/{id}/_api/web/lists", paths.get(0).asText());
        assertFalse(json.toString().contains("SECRET"));
        assertFalse(json.path("diagnostic").toString().contains("contoso"));
        assertFalse(json.path("diagnostic").toString().contains("ProjetIAM"));
    }

    // ------------------------------------------------------------------ teams_read_docx (SF-108-06)

    private Path docx(String name, String documentXml) throws Exception {
        Path file = home.resolve(name);
        try (java.util.zip.ZipOutputStream zip =
                new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    @Test
    @DisplayName("Lire un .docx local : texte relayé, paragraphes, aucun geste, aucune confirmation")
    void reads_a_local_docx() throws Exception {
        PaperTeams teams = new PaperTeams();
        Path file = docx("transcription.docx", "<w:document xmlns:w=\"x\"><w:body>"
                + "<w:p><w:r><w:t>Point un</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>Point deux</w:t></w:r></w:p></w:body></w:document>");

        JsonNode json = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.READ_DOCX,
                mapper.createObjectNode().put("file", file.toString()));

        assertTrue(json.path("read").asBoolean(), json.toString());
        assertEquals("Point un\nPoint deux", json.path("documentText").asText());
        assertTrue(json.path("text").asText().contains("Point un"), json.path("text").asText());
        assertEquals(2, json.path("paragraphs").asInt());
        assertFalse(json.path("truncated").asBoolean());
        // Une lecture LOCALE : aucun geste dans le navigateur.
        assertTrue(teams.browser.navigations().isEmpty());
        assertTrue(teams.browser.scripts().isEmpty());
    }

    @Test
    @DisplayName("Un fichier qui n'est pas un .docx : BODY_UNAVAILABLE, rien d'inventé")
    void a_non_docx_is_named() throws Exception {
        PaperTeams teams = new PaperTeams();
        Path file = home.resolve("pasun.docx");
        Files.writeString(file, "ceci n'est pas un docx");

        JsonNode json = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.READ_DOCX,
                mapper.createObjectNode().put("file", file.toString()));

        assertFalse(json.path("read").asBoolean());
        assertEquals("BODY_UNAVAILABLE", json.path("gaps").get(0).path("kind").asText());
    }

    @Test
    @DisplayName("Chemin relatif ou fichier absent : refus nommé, rien n'est lu")
    void a_relative_or_missing_path_is_refused() throws Exception {
        PaperTeams teams = new PaperTeams();

        JsonNode relative = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.READ_DOCX,
                mapper.createObjectNode().put("file", "transcription.docx"));
        JsonNode absent = call(teams.toolsWithFiles(root, nothingSynced()), TeamsTools.READ_DOCX,
                mapper.createObjectNode().put("file", home.resolve("absent.docx").toString()));

        assertFalse(relative.path("read").asBoolean());
        assertTrue(relative.path("gaps").get(0).path("detail").asText().contains("ABSOLU"));
        assertEquals("NOT_FOUND", absent.path("gaps").get(0).path("kind").asText());
    }
}
