package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * F-108 / SF-108-04 — <b>les six écritures</b>, vues de l'extérieur, contre des réponses modèles de
 * l'API REST SharePoint. La confirmation, elle, est éprouvée côté gateway (SF-108-02) : ici, l'écriture
 * a déjà été autorisée.
 */
class TeamsWriteToolsTest {

    static final String GENERAL = TeamsFileToolsTest.GENERAL;
    static final String PLAN = TeamsFileToolsTest.PLAN;
    /** Un autre site du MÊME hôte (contoso.sharepoint.com) : la copie y est vérifiable. */
    static final String FINANCE = "https://contoso.sharepoint.com/sites/Finance/Shared%20Documents";
    /** Un AUTRE hôte (OneDrive professionnel) : la copie ne s'y vérifie pas depuis cet onglet. */
    static final String ONEDRIVE = "https://contoso-my.sharepoint.com/personal/u_contoso_com/Documents";

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path root;

    @TempDir
    Path home;

    private TeamsTools tools(PaperTeams teams) {
        return teams.toolsWithFiles(root, new SyncedLibraries(home, Map.of(), OperatingSystem.LINUX));
    }

    private JsonNode call(TeamsTools tools, String tool, ObjectNode input) throws Exception {
        ToolOutcome outcome = tools.execute(tool, input);
        assertTrue(outcome.ok(), outcome.errorMessage());
        return mapper.readTree(outcome.content());
    }

    private ObjectNode ask() {
        return mapper.createObjectNode();
    }

    private static boolean ran(PaperTeams teams, String operation) {
        return teams.browser.scripts().stream().anyMatch(script -> script.contains("/*cg-op:" + operation + "*/"));
    }

    private Path localFile(String content) throws Exception {
        Path file = home.resolve("rapport.docx");
        Files.writeString(file, content);
        return file;
    }

    // ------------------------------------------------------------------ créer un dossier

    @Test
    @DisplayName("Créer un dossier : existence vérifiée, POST /_api/web/folders, vue remise, résultat vérifié")
    void create_folder() throws Exception {
        PaperTeams teams = new PaperTeams();
        String teamsRoute = teams.browser.route();
        teams.browser.sharePoint("verifier-le-fichier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("verifier-le-dossier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("creer-le-dossier", 200, TeamsSamples.read("sharepoint-folder-created.json"));

        JsonNode json = call(tools(teams), TeamsTools.CREATE_FOLDER,
                ask().put("location", GENERAL).put("name", "Livrables 2026"));

        assertTrue(json.path("done").asBoolean(), json.toString());
        assertTrue(json.path("text").asText().startsWith("C'est fait"), json.path("text").asText());
        assertEquals("ProjetIAM › Shared Documents › General › Livrables 2026",
                json.path("item").path("label").asText());
        assertTrue(teams.browser.scripts().stream().anyMatch(script -> script.contains("/_api/web/folders")
                && script.contains("/_api/contextinfo") && script.contains("X-RequestDigest")));
        assertEquals(teamsRoute, teams.browser.route());
        assertTrue(json.path("viewport").asText().contains("remis"));
        assertEquals(SharePointFiles.PROVENANCE, json.path("provenance").asText());
    }

    @Test
    @DisplayName("Dossier déjà là : ALREADY_EXISTS et AUCUNE écriture")
    void existing_folder_is_not_created() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.sharePoint("verifier-le-fichier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("verifier-le-dossier", 200, TeamsSamples.read("sharepoint-folder-created.json"));

        JsonNode json = call(tools(teams), TeamsTools.CREATE_FOLDER,
                ask().put("location", GENERAL).put("name", "Livrables 2026"));

        assertFalse(json.path("done").asBoolean());
        assertEquals("ALREADY_EXISTS", json.path("gaps").get(0).path("kind").asText());
        assertFalse(ran(teams, "creer-le-dossier"));
    }

    @Test
    @DisplayName("Nom invalide : INVALID_NAME, aucune navigation, aucun script")
    void invalid_name_moves_nothing() throws Exception {
        PaperTeams teams = new PaperTeams();

        JsonNode json = call(tools(teams), TeamsTools.CREATE_FOLDER,
                ask().put("location", GENERAL).put("name", "a/b:c"));

        assertEquals("INVALID_NAME", json.path("gaps").get(0).path("kind").asText());
        assertTrue(teams.browser.navigations().isEmpty());
        assertTrue(teams.browser.scripts().isEmpty());
    }

    @Test
    @DisplayName("Réponse OK mais non conforme : jamais « c'est fait », « a PEUT-ÊTRE eu lieu »")
    void an_unexpected_success_is_never_called_done() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.sharePoint("verifier-le-fichier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("verifier-le-dossier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("creer-le-dossier", 200, TeamsSamples.read("sharepoint-moveto.json"));

        JsonNode json = call(tools(teams), TeamsTools.CREATE_FOLDER,
                ask().put("location", GENERAL).put("name", "Livrables 2026"));

        assertFalse(json.path("done").asBoolean());
        assertTrue(json.path("unsure").asBoolean());
        assertTrue(json.path("text").asText().contains("PEUT-ÊTRE"), json.path("text").asText());
        assertFalse(json.path("text").asText().contains("C'est fait"));
        assertEquals("SHAPE_MISMATCH", json.path("gaps").get(0).path("kind").asText());
    }

    // ------------------------------------------------------------------ déposer

    @Test
    @DisplayName("Déposer : champ créé par le script, fichier posé par DOM.setFileInputFiles, taille vérifiée")
    void upload_a_local_file() throws Exception {
        PaperTeams teams = new PaperTeams();
        Path local = localFile("CONTENU-DU-RAPPORT-24oct");
        teams.browser.sharePoint("verifier-le-fichier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("verifier-le-dossier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("deposer-le-fichier", 200, TeamsSamples.read("sharepoint-file-uploaded.json"));

        JsonNode json = call(tools(teams), TeamsTools.UPLOAD_FILE,
                ask().put("file", local.toString()).put("location", GENERAL));

        assertTrue(json.path("done").asBoolean(), json.toString());
        assertEquals(local.toString(), teams.browser.droppedFiles().get(0));
        assertTrue(teams.browser.sentCommands().contains(CdpCommands.SET_FILE_INPUT_FILES));
        // Les octets du fichier ne passent jamais par la liaison : Chrome lit le disque.
        assertTrue(teams.browser.scripts().stream().noneMatch(script -> script.contains("CONTENU-DU-RAPPORT")));
        assertTrue(teams.browser.scripts().stream().anyMatch(script ->
                script.contains("AddUsingPath") && script.contains("Overwrite=false")));
        assertEquals(local.toString(), json.path("localFile").asText());
    }

    @Test
    @DisplayName("Déposer par-dessus un fichier existant : refus nommé, remède = remplacer la version")
    void upload_never_overwrites() throws Exception {
        PaperTeams teams = new PaperTeams();
        Path local = localFile("CONTENU-DU-RAPPORT-24oct");
        teams.browser.sharePoint("verifier-le-fichier", 200, TeamsSamples.read("sharepoint-file-uploaded.json"));

        JsonNode json = call(tools(teams), TeamsTools.UPLOAD_FILE,
                ask().put("file", local.toString()).put("location", GENERAL));

        assertEquals("ALREADY_EXISTS", json.path("gaps").get(0).path("kind").asText());
        assertTrue(json.path("gaps").get(0).path("detail").asText().contains(TeamsTools.REPLACE_VERSION));
        assertFalse(ran(teams, "deposer-le-fichier"));
    }

    @Test
    @DisplayName("Fichier local absent ou relatif : refus AVANT tout geste")
    void a_missing_local_file_moves_nothing() throws Exception {
        PaperTeams teams = new PaperTeams();

        JsonNode absent = call(tools(teams), TeamsTools.UPLOAD_FILE,
                ask().put("file", home.resolve("absent.docx").toString()).put("location", GENERAL));
        JsonNode relative = call(tools(teams), TeamsTools.UPLOAD_FILE,
                ask().put("file", "rapport.docx").put("location", GENERAL));

        assertEquals("NOT_FOUND", absent.path("gaps").get(0).path("kind").asText());
        assertTrue(relative.path("gaps").get(0).path("detail").asText().contains("ABSOLU"));
        assertTrue(teams.browser.navigations().isEmpty());
    }

    // ------------------------------------------------------------------ remplacer une version

    @Test
    @DisplayName("Remplacer une version : versions avant / après, historique restaurable dit")
    void replace_a_version() throws Exception {
        PaperTeams teams = new PaperTeams();
        Path local = localFile("CONTENU-DU-RAPPORT-24oct");
        teams.browser.sharePoint("verifier-le-fichier", 200, TeamsSamples.read("sharepoint-file.json"))
                .sharePoint("remplacer-la-version", 200, TeamsSamples.read("sharepoint-file-replaced.json"));

        JsonNode json = call(tools(teams), TeamsTools.REPLACE_VERSION,
                ask().put("target", PLAN).put("file", local.toString()));

        assertTrue(json.path("done").asBoolean(), json.toString());
        assertEquals("3.0", json.path("previousVersion").asText());
        assertEquals("4.0", json.path("item").path("version").asText());
        assertTrue(json.path("text").asText().contains("restaurable"));
        assertTrue(teams.browser.scripts().stream().anyMatch(script -> script.contains("Overwrite=true")));
    }

    @Test
    @DisplayName("Remplacer un fichier distant absent : « c'est un dépôt, pas un remplacement »")
    void replace_requires_an_existing_file() throws Exception {
        PaperTeams teams = new PaperTeams();
        Path local = localFile("x");
        teams.browser.sharePoint("verifier-le-fichier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("verifier-le-dossier", 404, TeamsSamples.read("sharepoint-error-404.json"));

        JsonNode json = call(tools(teams), TeamsTools.REPLACE_VERSION,
                ask().put("target", PLAN).put("file", local.toString()));

        assertEquals("NOT_FOUND", json.path("gaps").get(0).path("kind").asText());
        assertTrue(json.path("gaps").get(0).path("detail").asText().contains("pas un remplacement"));
        assertFalse(ran(teams, "remplacer-la-version"));
    }

    @Test
    @DisplayName("Fichier verrouillé (423) : WRITE_FAILED avec le message Microsoft")
    void a_locked_file_is_named() throws Exception {
        PaperTeams teams = new PaperTeams();
        Path local = localFile("CONTENU-DU-RAPPORT-24oct");
        teams.browser.sharePoint("verifier-le-fichier", 200, TeamsSamples.read("sharepoint-file.json"))
                .sharePoint("remplacer-la-version", 423, TeamsSamples.read("sharepoint-error-locked.json"));

        JsonNode json = call(tools(teams), TeamsTools.REPLACE_VERSION,
                ask().put("target", PLAN).put("file", local.toString()));

        assertFalse(json.path("done").asBoolean());
        assertEquals("WRITE_FAILED", json.path("gaps").get(0).path("kind").asText());
        assertTrue(json.path("gaps").get(0).path("detail").asText().contains("verrouillé"));
    }

    // ------------------------------------------------------------------ renommer, déplacer

    @Test
    @DisplayName("Renommer : nature lue, destination libre, MoveTo sans écrasement, nouvel emplacement revérifié")
    void rename_a_file() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.sharePoint("verifier-le-fichier", 200, TeamsSamples.read("sharepoint-file.json"))
                .sharePoint("verifier-le-fichier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("verifier-le-fichier", 200, TeamsSamples.read("sharepoint-file-replaced.json"))
                .sharePoint("verifier-le-dossier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("renommer-ou-deplacer", 200, TeamsSamples.read("sharepoint-moveto.json"));

        JsonNode json = call(tools(teams), TeamsTools.RENAME,
                ask().put("target", PLAN).put("name", "plan-v2.docx"));

        assertTrue(json.path("done").asBoolean(), json.toString());
        assertEquals("/sites/ProjetIAM/Shared Documents/General/plan-v2.docx",
                json.path("item").path("serverRelativeUrl").asText());
        assertTrue(teams.browser.scripts().stream().anyMatch(script -> script.contains("MoveTo(newurl=")
                && script.contains("flags=0")));
    }

    @Test
    @DisplayName("Déplacer vers un autre site : refus nommé, aucun geste")
    void move_across_sites_is_refused() throws Exception {
        PaperTeams teams = new PaperTeams();

        JsonNode json = call(tools(teams), TeamsTools.MOVE, ask().put("target", PLAN)
                .put("destination", "https://contoso.sharepoint.com/sites/Finance/Shared%20Documents"));

        assertFalse(json.path("done").asBoolean());
        assertTrue(json.path("gaps").get(0).path("detail").asText().contains("entre sites"));
        assertTrue(teams.browser.navigations().isEmpty());
    }

    // ------------------------------------------------------------------ supprimer

    @Test
    @DisplayName("Supprimer : à la CORBEILLE du site, identifiant rendu")
    void delete_goes_to_the_recycle_bin() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.sharePoint("verifier-le-fichier", 200, TeamsSamples.read("sharepoint-file.json"))
                .sharePoint("mettre-a-la-corbeille", 200, TeamsSamples.read("sharepoint-recycle.json"));

        JsonNode json = call(tools(teams), TeamsTools.DELETE, ask().put("target", PLAN));

        assertTrue(json.path("done").asBoolean(), json.toString());
        assertEquals("00000000-0000-0000-0000-0000000000b1", json.path("recycleBinItemId").asText());
        assertTrue(json.path("text").asText().contains("CORBEILLE"));
        assertTrue(teams.browser.scripts().stream().anyMatch(script -> script.contains("/recycle()")));
    }

    // ------------------------------------------------------------------ copier (SF-108-06)

    @Test
    @DisplayName("Copier vers un autre site du même hôte : CopyFileByPath, destination revérifiée, done")
    void copy_within_the_same_host_is_verified() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser
                .sharePoint("verifier-le-fichier", 200, TeamsSamples.read("sharepoint-file.json"))
                .sharePoint("verifier-le-fichier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("verifier-le-dossier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("copier", 200, TeamsSamples.read("sharepoint-copy.json"))
                .sharePoint("verifier-le-fichier", 200, TeamsSamples.read("sharepoint-file.json"));

        JsonNode json = call(tools(teams), TeamsTools.COPY,
                ask().put("target", PLAN).put("destination", FINANCE));

        assertTrue(json.path("done").asBoolean(), json.toString());
        assertTrue(json.path("text").asText().startsWith("C'est fait"), json.path("text").asText());
        assertFalse(json.path("verifyAtDestination").asBoolean());
        assertEquals("Finance › Shared Documents › plan-migration.docx",
                json.path("item").path("label").asText());
        assertTrue(teams.browser.scripts().stream().anyMatch(script ->
                script.contains("SP.MoveCopyUtil.CopyFileByPath") && script.contains("overwrite: false")));
        assertTrue(json.path("viewport").asText().contains("remis"));
    }

    @Test
    @DisplayName("Copier vers un AUTRE hôte : réponse OK mais non vérifiable, jamais « c'est fait »")
    void copy_across_hosts_is_never_called_done() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser
                .sharePoint("verifier-le-fichier", 200, TeamsSamples.read("sharepoint-file.json"))
                .sharePoint("copier", 200, TeamsSamples.read("sharepoint-copy.json"));

        JsonNode json = call(tools(teams), TeamsTools.COPY,
                ask().put("target", PLAN).put("destination", ONEDRIVE));

        assertFalse(json.path("done").asBoolean(), json.toString());
        assertTrue(json.path("verifyAtDestination").asBoolean());
        assertTrue(json.path("text").asText().contains("VÉRIFIE"), json.path("text").asText());
        assertFalse(json.path("text").asText().contains("C'est fait"));
        // La destination est bien portée dans l'appel de copie, sur l'autre hôte.
        assertTrue(teams.browser.scripts().stream().anyMatch(script ->
                script.contains("SP.MoveCopyUtil.CopyFileByPath")
                        && script.contains("contoso-my.sharepoint.com")));
    }

    @Test
    @DisplayName("Copier une source absente : NOT_FOUND, rien n'est copié")
    void copy_requires_an_existing_source() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser
                .sharePoint("verifier-le-fichier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("verifier-le-dossier", 404, TeamsSamples.read("sharepoint-error-404.json"));

        JsonNode json = call(tools(teams), TeamsTools.COPY,
                ask().put("target", PLAN).put("destination", FINANCE));

        assertFalse(json.path("done").asBoolean());
        assertEquals("NOT_FOUND", json.path("gaps").get(0).path("kind").asText());
        assertFalse(ran(teams, "copier"));
    }

    @Test
    @DisplayName("Copier là où un élément existe déjà (même hôte) : ALREADY_EXISTS, rien n'est écrasé")
    void copy_never_overwrites() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser
                .sharePoint("verifier-le-fichier", 200, TeamsSamples.read("sharepoint-file.json"))
                .sharePoint("verifier-le-fichier", 200, TeamsSamples.read("sharepoint-file.json"));

        JsonNode json = call(tools(teams), TeamsTools.COPY,
                ask().put("target", PLAN).put("destination", FINANCE));

        assertFalse(json.path("done").asBoolean());
        assertEquals("ALREADY_EXISTS", json.path("gaps").get(0).path("kind").asText());
        assertFalse(ran(teams, "copier"));
    }

    // ------------------------------------------------------------------ gros fichiers (SF-108-06)

    @Test
    @DisplayName("Déposer au-delà de 250 Mo : session d'envoi découpée, aucun octet dans la liaison")
    void a_large_file_goes_through_a_chunked_upload() throws Exception {
        PaperTeams teams = new PaperTeams();
        Path big = sparseFile("gros.bin", 300L * 1024 * 1024);
        teams.browser
                .sharePoint("verifier-le-fichier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("verifier-le-dossier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("deposer-par-fragments", 200,
                        TeamsSamples.read("sharepoint-file-uploaded-large.json"));

        JsonNode json = call(tools(teams), TeamsTools.UPLOAD_FILE,
                ask().put("file", big.toString()).put("location", GENERAL));

        assertTrue(json.path("done").asBoolean(), json.toString());
        assertEquals(big.toString(), teams.browser.droppedFiles().get(0));
        assertTrue(teams.browser.sentCommands().contains(CdpCommands.SET_FILE_INPUT_FILES));
        assertTrue(teams.browser.scripts().stream().anyMatch(script ->
                script.contains("AddUsingPath") && script.contains("StartUpload")
                        && script.contains("ContinueUpload") && script.contains("FinishUpload")));
        // Le fichier découpé n'a jamais servi le chemin simple d'un bloc.
        assertFalse(ran(teams, "deposer-le-fichier"));
        assertEquals(314572800L, json.path("item").path("size").asLong());
    }

    @Test
    @DisplayName("Fichier au-delà du plafond dur (15 Gio) : refus AVANT tout geste")
    void a_file_over_the_hard_cap_moves_nothing() throws Exception {
        PaperTeams teams = new PaperTeams();
        Path enormous = sparseFile("enorme.bin", 15L * 1024 * 1024 * 1024 + 1);

        JsonNode json = call(tools(teams), TeamsTools.UPLOAD_FILE,
                ask().put("file", enormous.toString()).put("location", GENERAL));

        assertFalse(json.path("done").asBoolean());
        assertEquals("WRITE_FAILED", json.path("gaps").get(0).path("kind").asText());
        assertTrue(json.path("gaps").get(0).path("detail").asText().contains("15 Gio"));
        assertTrue(teams.browser.navigations().isEmpty());
        assertTrue(teams.browser.scripts().isEmpty());
    }

    @Test
    @DisplayName("Page qui renverrait tout : aucun secret ne sort d'une copie")
    void nothing_secret_leaves_a_copy() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser
                .sharePoint("verifier-le-fichier", 200, TeamsSamples.read("sharepoint-file.json"))
                .sharePointUnfiltered("copier", TeamsSamples.read("sharepoint-files-secrets.json"));

        ToolOutcome outcome = tools(teams).execute(TeamsTools.COPY,
                ask().put("target", PLAN).put("destination", ONEDRIVE));

        assertFalse(outcome.content().contains("SECRET"), outcome.content());
        assertFalse(outcome.content().contains("tempauth"), outcome.content());
    }

    /** Un fichier creux : la taille logique demandée, sans écrire les octets sur le disque. */
    private Path sparseFile(String name, long size) throws Exception {
        Path file = home.resolve(name);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(size);
        }
        return file;
    }

    // ------------------------------------------------------------------ gardes

    @Test
    @DisplayName("Page d'identification : aucun script d'écriture, SIGNED_OUT")
    void a_sign_in_page_writes_nothing() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.redirectingTo("https://login.microsoftonline.com/common/oauth2/authorize");

        JsonNode json = call(tools(teams), TeamsTools.DELETE, ask().put("target", PLAN));

        assertTrue(teams.browser.scripts().isEmpty());
        assertEquals("SIGNED_OUT", json.path("gaps").get(0).path("kind").asText());
        assertFalse(json.path("done").asBoolean());
    }

    @Test
    @DisplayName("Page qui renverrait tout : ni digest, ni jeton, ni adresse pré-authentifiée ne ressortent")
    void nothing_secret_leaves_a_write() throws Exception {
        PaperTeams teams = new PaperTeams();
        teams.browser.sharePoint("verifier-le-fichier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePoint("verifier-le-dossier", 404, TeamsSamples.read("sharepoint-error-404.json"))
                .sharePointUnfiltered("creer-le-dossier", TeamsSamples.read("sharepoint-files-secrets.json"));

        ToolOutcome outcome = tools(teams).execute(TeamsTools.CREATE_FOLDER,
                ask().put("location", GENERAL).put("name", "Livrables 2026"));

        assertFalse(outcome.content().contains("SECRET"), outcome.content());
        assertFalse(outcome.content().contains("tempauth"), outcome.content());
    }
}
