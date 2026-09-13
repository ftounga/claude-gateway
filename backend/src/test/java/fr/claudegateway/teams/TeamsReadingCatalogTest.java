package fr.claudegateway.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;

/**
 * F-88 / SF-88-03 — <b>le catalogue réellement donné à l'agent</b>.
 *
 * <p>Jusqu'ici, les huit outils existaient dans le runner, éprouvés, et l'agent ne pouvait appeler
 * que {@code teams_status}. Ce test vérifie que la chaîne est fermée — et que la <b>garde</b> de
 * F-89 / SF-89-01 l'est restée : le catalogue a grandi, le droit décide toujours.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamsReadingCatalogTest {

    /**
     * Le catalogue attendu, <b>écrit à la main</b>. Sa contrepartie côté runner
     * ({@code TeamsTools.CATALOG}) est verrouillée par son propre test : le runner n'est pas au
     * classpath de la gateway, et deux verrous qui se répondent valent mieux qu'un accord tacite.
     */
    private static final List<String> EXPECTED = List.of(
            "teams_status", "teams_find_conversations", "teams_read_conversation", "teams_mentions",
            "teams_search", "teams_find_meetings", "teams_meeting_transcript",
            "teams_meeting_recording",
            // F-90 / SF-90-03 — les deux outils de captures. Ce sont bien des outils de LECTURE au
            // sens de ce catalogue : le RUNNER les exécute, contrairement aux outils de
            // présentation qui ne quittent jamais la gateway.
            "teams_meeting_moments", "teams_moments_status",
            // F-91 / SF-91-02 — les trois outils d'ENREGISTREMENT LOCAL. Ils sont dans cette liste
            // parce que le RUNNER les exécute, comme les autres ; ce qui les distingue n'est pas où
            // ils tournent, c'est qu'ils CRÉENT au lieu de relire (voir TeamsToolCatalog.CAPTURE).
            "teams_capture_start", "teams_capture_stop", "teams_capture_status",
            // F-108 / SF-108-03 — les deux outils de LECTURE des fichiers Microsoft 365.
            "teams_list_files", "teams_read_file",
            // F-108 / SF-108-04 — les six ÉCRITURES : le runner les exécute, après autorisation.
            "teams_create_folder", "teams_upload_file", "teams_rename", "teams_move", "teams_delete",
            "teams_replace_version");

    @Mock private TeamsAccessService teamsAccess;

    private TeamsToolCatalog catalog;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        catalog = new TeamsToolCatalog(teamsAccess);
        when(teamsAccess.hasAccess(userId)).thenReturn(true);
    }

    private static Workspace teamsTerminal() {
        Workspace workspace = new Workspace();
        workspace.setTeamsTerminal(true);
        return workspace;
    }

    private List<AgentTool> tools() {
        return catalog.toolsFor(userId, teamsTerminal());
    }

    @Test
    @DisplayName("Les TREIZE outils que le RUNNER exécute sont donnés, dans l'ordre et EN PREMIER")
    void the_whole_catalog_is_given() {
        // Depuis F-89 / SF-89-02, la panoplie d'un terminal Teams porte aussi les outils de
        // PRÉSENTATION — ceux qui posent un bloc dans le fil et ne quittent jamais la gateway. Le
        // catalogue de LECTURE, lui, ne grandit qu'avec ce que le RUNNER sait exécuter : depuis
        // F-90 / SF-90-03, ces dix-là, dans cet ordre, et c'est exactement ce que le runner
        // mirroite.
        assertThat(tools()).extracting(AgentTool::name).startsWith(EXPECTED.toArray(String[]::new));
    }

    @Test
    @DisplayName("Le catalogue déclaré et la constante CATALOG ne peuvent pas diverger")
    void the_declared_catalog_matches_the_constant() {
        assertThat(TeamsToolCatalog.CATALOG).containsExactlyElementsOf(EXPECTED);
        assertThat(tools()).extracting(AgentTool::name)
                .containsSequence(TeamsToolCatalog.CATALOG);
        // Et LE RUNNER NE CONNAÎT AUCUN outil de présentation : les y ajouter le ferait échouer sur
        // des outils qu'il ne sait pas exécuter, alors qu'ils s'exécutent chez nous.
        assertThat(TeamsToolCatalog.CATALOG)
                .doesNotContainAnyElementsOf(TeamsToolCatalog.PRESENTATION);
    }

    @Test
    @DisplayName("Le catalogue a grandi, la garde n'a pas bougé : sans droit, RIEN")
    void the_bigger_catalog_is_still_behind_the_right() {
        when(teamsAccess.hasAccess(userId)).thenReturn(false);

        assertThat(tools()).isEmpty();
    }

    @Test
    @DisplayName("Sur un terminal de projet, RIEN — même avec le droit (non-régression F-89)")
    void a_project_terminal_gets_nothing() {
        assertThat(catalog.toolsFor(userId, new Workspace())).isEmpty();
    }

    @Test
    @DisplayName("Le droit consulté est celui du tour : un autre utilisateur ne donne rien")
    void the_right_is_read_for_the_turn_owner() {
        assertThat(catalog.toolsFor(UUID.randomUUID(), teamsTerminal())).isEmpty();
    }

    @Test
    @DisplayName("D4 — la description de la lecture ANNONCE le plafond et le dit NÉGOCIABLE")
    void the_cap_is_announced_and_said_negotiable() {
        String description = describe("teams_read_conversation");

        assertThat(description).contains("7 jours").contains("500 messages")
                .contains("NÉGOCIABLE").contains("2000");
    }

    @Test
    @DisplayName("La lecture dit que le résultat porte la fenêtre RÉELLEMENT lue et les manques")
    void the_reading_tool_tells_the_agent_to_repeat_the_gaps() {
        String description = describe("teams_read_conversation");

        assertThat(description).contains("RÉELLEMENT lue").contains("gaps")
                .contains("jamais une lecture incomplète comme complète");
    }

    @Test
    @DisplayName("Les trois gisements sont nommés là où l'agent les lit")
    void the_three_seams_are_named_in_the_descriptions() {
        assertThat(describe("teams_mentions")).contains("mentions explicites")
                .contains("sans vous mentionner n'y est pas");
        assertThat(describe("teams_search")).contains("sans vous mentionner")
                .contains("variantes du nom");
        assertThat(describe("teams_read_conversation"))
                .contains("engagements qu'on a pris soi-même")
                .contains("aucune recherche ne les trouve");
    }

    @Test
    @DisplayName("F-91 — l'arrêt dit que la transcription est LOCALE, LONGUE, et que rien ne sort")
    void the_stop_tool_says_the_transcription_is_local() {
        assertThat(describe("teams_capture_stop"))
                .contains("SUR LA MACHINE")
                .contains("Rien ne sort de la machine")
                .contains("NE CONCLUS PAS")
                .contains("capture_id");
    }

    @Test
    @DisplayName("F-91 — l'état interdit de deviner un locuteur que le moteur local ne donne pas")
    void the_status_tool_forbids_guessing_the_speaker() {
        assertThat(describe("teams_capture_status")).contains("ne dit PAS qui parle");
    }

    @Test
    @DisplayName("F-91 — les moments PRÉFÈRENT capture_id : il apporte l'origine du temps exacte")
    void the_moments_tool_prefers_the_capture() {
        assertThat(String.valueOf(schema("teams_meeting_moments").get("properties")))
                .contains("capture_id");
        assertThat(describe("teams_meeting_moments")).isNotBlank();
    }

    @Test
    @DisplayName("F-91 — les blocs savent porter la mention, et la description l'ORDONNE")
    void the_presentation_tools_carry_the_notice() {
        for (String tool : List.of("teams_meeting_card", "teams_list", "teams_moments")) {
            assertThat(String.valueOf(schema(tool).get("properties")))
                    .as(tool)
                    .contains("recordingNotice")
                    .contains("recopie TEL QUEL");
        }
    }

    private Map<String, Object> schema(String tool) {
        return tools().stream().filter(candidate -> candidate.name().equals(tool)).findFirst()
                .orElseThrow().inputSchema();
    }

    @Test
    @DisplayName("L'enregistrement dit à l'agent de NE PAS laisser croire qu'un fichier existe")
    void the_recording_tool_warns_the_agent() {
        // F-108 / SF-108-05 : il télécharge — par Chrome — et la règle d'origine reste écrite.
        assertThat(describe("teams_meeting_recording"))
                .contains("c'est Chrome qui le télécharge").contains("aucune adresse signée")
                .contains("ne laisse jamais croire").contains("« downloaded » est faux");
        assertThat(String.valueOf(schema("teams_meeting_recording").get("properties")))
                .contains("recording_url").contains("download");
    }

    @Test
    @DisplayName("La transcription interdit d'inventer ce qui n'a pas été lu")
    void the_transcript_tool_forbids_invention() {
        assertThat(describe("teams_meeting_transcript")).contains("ne faut surtout pas en inventer");
    }

    @Test
    @DisplayName("Les deux outils qui exigent un paramètre le déclarent obligatoire")
    void required_parameters_are_declared() {
        assertThat(required("teams_search")).containsExactly("query");
        assertThat(required("teams_meeting_transcript")).containsExactly("meeting_id");
        assertThat(required("teams_meeting_recording")).containsExactly("meeting_id");
        assertThat(required("teams_read_conversation")).isEmpty();
    }

    @Test
    @DisplayName("Tout ce que le catalogue rend porte le préfixe teams_")
    void every_tool_still_carries_the_prefix() {
        assertThat(tools()).allSatisfy(
                tool -> assertThat(tool.name()).startsWith(TeamsToolCatalog.PREFIX));
    }

    // ------------------------------------------------------------------ utilitaires

    private String describe(String name) {
        return tools().stream().filter(tool -> tool.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("outil absent du catalogue : " + name))
                .description();
    }

    @SuppressWarnings("unchecked")
    private List<String> required(String name) {
        Map<String, Object> schema = tools().stream()
                .filter(tool -> tool.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("outil absent : " + name))
                .inputSchema();
        Object required = schema.get("required");
        return required == null ? List.of() : (List<String>) required;
    }
}
