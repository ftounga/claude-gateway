package fr.claudegateway.atelier.scene;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentContentBlock;

/** F-234 / SF-234-04 (D5) — les étiquettes de scène des outils muets, et l'étape web des outils serveur. */
class SceneStepLabelsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    @Test
    @DisplayName("update_plan_step ⇒ « chantier 3 · étape 2 · faite » ; sans chantier ⇒ l'étape")
    void planStep() throws Exception {
        assertThat(SceneStepLabels.label("update_plan_step", json("{\"step\":2,\"status\":\"FAIT\",\"chantier\":3,"
                + "\"evidence\":\"preuve longue\"}"))).isEqualTo("chantier 3 · étape 2 · faite");
        assertThat(SceneStepLabels.label("update_plan_step", json("{\"step\":4,\"status\":\"BLOQUE\"}")))
                .isEqualTo("étape 4 · bloquée");
        assertThat(SceneStepLabels.label("set_subject_plan", json("{\"steps\":[{},{},{}]}")))
                .isEqualTo("plan en 3 étapes");
    }

    @Test
    @DisplayName("record_blocker ⇒ personne + catégorie ; jamais la description")
    void blockers() throws Exception {
        String label = SceneStepLabels.label("record_blocker", json("{\"person\":\"Zahi\",\"categorie\":\"VALIDATION\","
                + "\"description\":\"SECRET-DESCRIPTION\"}"));
        assertThat(label).isEqualTo("Zahi · validation").doesNotContain("SECRET");
        assertThat(SceneStepLabels.label("record_blocker", json("{\"person\":\"Karim\",\"kind\":\"MESSAGE\"}")))
                .isEqualTo("Karim · message");
        assertThat(SceneStepLabels.label("update_blocker", json("{\"requested_to\":\"Karim\",\"status\":\"DEMANDE\"}")))
                .isEqualTo("Karim · demandé");
        assertThat(SceneStepLabels.label("close_blocker", json("{\"key\":\"k\",\"reason\":\"x\"}"))).isEqualTo("levée");
    }

    @Test
    @DisplayName("passation, skill, veille, document : leur nom ; un outil inconnu : rien")
    void names() throws Exception {
        assertThat(SceneStepLabels.label("ouvrir_sujet", json("{\"sujet\":\"Migration Forge\",\"phrase\":\"x\"}")))
                .isEqualTo("Migration Forge");
        assertThat(SceneStepLabels.label("skill", json("{\"nom\":\"deploy\"}"))).isEqualTo("deploy");
        assertThat(SceneStepLabels.label("veille_poser", json("{\"plateforme\":\"Jira\",\"nom\":\"AGENOR\"}")))
                .isEqualTo("Jira · AGENOR");
        assertThat(SceneStepLabels.label("build_document", json("{\"filename\":\"note-codir.docx\"}")))
                .isEqualTo("note-codir.docx");
        assertThat(SceneStepLabels.label("teams_read_conversation", json("{\"id\":\"x\"}"))).isNull();
        assertThat(SceneStepLabels.label("reopen_investigation", json("{\"reason\":\"contenu\"}"))).isNull();
    }

    @Test
    @DisplayName("bornée à 120 caractères")
    void bounded() throws Exception {
        String label = SceneStepLabels.label("ouvrir_sujet", json("{\"sujet\":\"" + "x".repeat(300) + "\"}"));
        assertThat(label).hasSize(SceneStepLabels.MAX_CHARS);
    }

    @Test
    @DisplayName("web_search ⇒ la requête ; web_fetch ⇒ le domaine seul ; les résultats ne font pas d'étape")
    void webSteps() throws Exception {
        List<AgentContentBlock> blocks = List.of(
                new AgentContentBlock.ServerTool(json("{\"type\":\"server_tool_use\",\"name\":\"web_search\","
                        + "\"input\":{\"query\":\"TTL DNS route53\"}}")),
                new AgentContentBlock.ServerTool(json("{\"type\":\"web_search_tool_result\",\"content\":[]}")),
                new AgentContentBlock.ServerTool(json("{\"type\":\"server_tool_use\",\"name\":\"web_fetch\","
                        + "\"input\":{\"url\":\"https://www.example.com/private/path?token=abc\"}}")),
                new AgentContentBlock.Text("bonjour"));
        assertThat(SceneStepLabels.webSteps(blocks)).containsExactly(
                new SceneStepLabels.WebStep("web_search", "TTL DNS route53"),
                new SceneStepLabels.WebStep("web_fetch", "example.com"));
        assertThat(SceneStepLabels.webSteps(null)).isEmpty();
        assertThat(SceneStepLabels.domain("pas une url")).isNull();
    }
}
