package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.OperatingSystem;
import fr.claudegateway.runner.ToolContext;
import fr.claudegateway.runner.ToolOutcome;

/**
 * <b>Les deux outils de captures, vus de l'extérieur</b> (F-90 / SF-90-03) — ce que l'agent reçoit
 * réellement.
 *
 * <p>Ce qui est vérifié : l'outil <b>rend la main tout de suite</b>, il <b>diffuse</b> ses étapes
 * dans le fil, l'annonce D1 paraît <b>une fois</b> et dit qu'<b>une capture est plus indiscrète
 * qu'une phrase</b>, et un identifiant de travail inconnu rend <b>zéro moment et un manque
 * nommé</b> — jamais une réponse vide qui se lirait « rien trouvé ».</p>
 */
class TeamsMomentsToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> streamed = new ArrayList<>();

    @TempDir
    Path home;

    @Test
    void loutil_rend_la_main_tout_de_suite_et_diffuse_ses_etapes() throws Exception {
        TeamsTools tools = tools();
        JsonNode started = call(tools, TeamsTools.MEETING_MOMENTS,
                ask().put("video", video().toString()).put("video_started_at",
                        "2026-09-12T14:00:00Z"));

        // La main est rendue AVANT la fin : le travail n'est pas terminé à cet instant.
        assertFalse(started.path("done").asBoolean(), "traitement lourd, donc asynchrone");
        assertFalse(started.path("jobId").asText("").isBlank());
        assertTrue(started.path("text").asText().contains("En cours"));

        waitForEnd(tools, started.path("jobId").asText());
        assertTrue(streamed.stream().anyMatch(line -> line.contains("ffmpeg")),
                "le travail se voit travailler, comme une commande longue");
    }

    @Test
    void lannonce_de_portee_parait_une_fois_et_dit_lindiscretion_dune_capture() throws Exception {
        TeamsTools tools = tools();
        ObjectNode input = ask().put("video", video().toString())
                .put("video_started_at", "2026-09-12T14:00:00Z");

        JsonNode first = call(tools, TeamsTools.MEETING_MOMENTS, input);
        String notice = first.path("notice").asText("");

        assertTrue(notice.contains("plus indiscrète qu'une phrase"));
        assertTrue(notice.contains("VISIBLE"));
        assertTrue(notice.contains("restent sur cette machine"));
        assertTrue(notice.contains("supprimé avec lui"), "D2 est dit dans la même phrase");

        waitForEnd(tools, first.path("jobId").asText());
        JsonNode second = call(tools, TeamsTools.MEETING_MOMENTS, input);
        assertEquals("", second.path("notice").asText(""),
                "une annonce répétée cesse d'être lue : D1 la veut UNE fois");
    }

    @Test
    void sans_chemin_de_video_cest_un_manque_nomme_et_aucun_travail() throws Exception {
        JsonNode result = call(tools(), TeamsTools.MEETING_MOMENTS, ask());

        assertEquals(0, result.path("moments").size());
        assertEquals("MISSING_FIELD", result.path("gaps").get(0).path("kind").asText());
        assertTrue(result.path("text").asText().contains("Je ne le télécharge pas depuis Teams"),
                "on rappelle POURQUOI on ne va pas le chercher tout seul");
    }

    @Test
    void un_identifiant_de_travail_inconnu_rend_zero_moment_et_un_manque_nomme() throws Exception {
        JsonNode result = call(tools(), TeamsTools.MOMENTS_STATUS,
                ask().put("job_id", "0123456789abcdef"));

        assertEquals(0, result.path("moments").size());
        assertEquals("NOTHING_OBSERVED", result.path("gaps").get(0).path("kind").asText());
        assertTrue(result.path("text").asText().contains("Je ne connais aucun travail"));
        assertFalse(result.path("text").asText().contains("rien trouvé"));
    }

    @Test
    void le_suivi_retrouve_le_travail_par_le_chemin_de_la_video() throws Exception {
        TeamsTools tools = tools();
        JsonNode started = call(tools, TeamsTools.MEETING_MOMENTS,
                ask().put("video", video().toString())
                        .put("video_started_at", "2026-09-12T14:00:00Z"));

        JsonNode status = call(tools, TeamsTools.MOMENTS_STATUS,
                ask().put("video", video().toString()));

        assertEquals(started.path("jobId").asText(), status.path("jobId").asText(),
                "l'identifiant est tiré du fichier : l'agent n'a pas à le retenir");
        waitForEnd(tools, started.path("jobId").asText());
    }

    @Test
    void sans_travail_de_captures_monte_loutil_le_dit_au_lieu_de_faire_semblant() throws Exception {
        TeamsTools bare = new PaperTeams().tools();

        JsonNode result = call(bare, TeamsTools.MEETING_MOMENTS,
                ask().put("video", video().toString()));

        assertEquals(0, result.path("moments").size());
        assertTrue(result.path("text").asText().contains("rien n'est remonté"));
    }

    @Test
    void le_catalogue_du_runner_porte_les_deux_outils_nouveaux() {
        assertTrue(TeamsTools.CATALOG.contains(TeamsTools.MEETING_MOMENTS));
        assertTrue(TeamsTools.CATALOG.contains(TeamsTools.MOMENTS_STATUS));
        // Treize depuis F-91 / SF-91-02, qui a ajouté les trois outils d'enregistrement local ;
        // quinze depuis F-108 / SF-108-03, qui ajoute la liste et la lecture des fichiers.
        assertEquals(15, TeamsTools.CATALOG.size());
        assertEquals(TeamsTools.CATALOG.size(), TeamsTools.CATALOG.stream().distinct().count());
    }

    @Test
    void la_liste_blanche_de_debogage_ne_bouge_pas() {
        // ffmpeg ne parle pas au navigateur : aucune commande CDP nouvelle n'est nécessaire.
        assertFalse(CdpCommands.allowed().stream()
                .anyMatch(command -> command.toLowerCase(java.util.Locale.ROOT).contains("moment")));
    }

    // ------------------------------------------------------------------ montage

    private ObjectNode ask() {
        return mapper.createObjectNode();
    }

    private JsonNode call(TeamsTools tools, String tool, ObjectNode input) throws Exception {
        ToolOutcome outcome = tools.execute(tool, input, context());
        assertTrue(outcome.ok(), "un outil Teams rend un état, pas une panne");
        return mapper.readTree(outcome.content());
    }

    private void waitForEnd(TeamsTools tools, String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            JsonNode status = call(tools, TeamsTools.MOMENTS_STATUS, ask().put("job_id", jobId));
            if (status.path("done").asBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("le travail n'a pas abouti dans le temps imparti");
    }

    private ToolContext context() {
        return new ToolContext() {
            @Override
            public void stream(String stream, String chunk) {
                streamed.add(chunk);
            }

            @Override
            public long timeoutMs() {
                return 60_000L;
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }

    private Path video() throws Exception {
        Path video = home.resolve("reunion.mp4");
        if (!Files.exists(video)) {
            Files.writeString(video, "un enregistrement de papier");
        }
        return video;
    }

    private TeamsTools tools() throws Exception {
        TeamsWorkFolder folder = new TeamsWorkFolder(home);
        Path binary = home.resolve("ffmpeg");
        if (!Files.exists(binary)) {
            Files.createFile(binary);
            binary.toFile().setExecutable(true);
        }
        LocalToolchain toolchain = new LocalToolchain(folder, OperatingSystem.LINUX,
                (command, dir, timeout) -> FakeProcesses.version("ffmpeg"),
                (url, into) -> 0L, message -> { }, name -> binary);
        // Un ffmpeg qui ne trouve aucun changement de plan : le travail aboutit sans moment, ce qui
        // suffit ici — les moments eux-mêmes sont éprouvés par MomentsWorkerTest.
        SceneFrames frames = new SceneFrames(toolchain, new FakeProcesses().succeedingWith(List.of()));
        MomentsWorker worker = new MomentsWorker(new MomentsJobStore(folder), frames,
                (workspaceId, image) -> "img-1");
        return new PaperTeams().tools().withMoments(worker);
    }
}
