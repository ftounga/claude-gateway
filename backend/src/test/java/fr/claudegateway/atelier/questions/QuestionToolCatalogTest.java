package fr.claudegateway.atelier.questions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.journey.JourneyPlan;
import fr.claudegateway.atelier.journey.JourneyRiskClassifier;

/** F-210 / SF-210-01 — des définitions stables, et des outils que la porte du mode guidé laisse passer. */
class QuestionToolCatalogTest {

    @Test
    void theDefinitionsAreStableAndOrdered() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String first = mapper.writeValueAsString(QuestionToolCatalog.tools());
        String second = mapper.writeValueAsString(QuestionToolCatalog.tools());

        assertThat(first).isEqualTo(second);
        assertThat(QuestionToolCatalog.tools()).extracting(AgentTool::name)
                .containsExactly("decouper_demande", "suivre_question");
        // Le schéma garde l'ordre écrit : questions avant fusion, id avant libelle.
        assertThat(first.indexOf("\"questions\"")).isLessThan(first.indexOf("\"fusion\""));
        assertThat(first).contains("QUE s'il y a au moins deux demandes distinctes");
    }

    @Test
    void theGuidedGateTreatsThemAsReads() {
        assertThat(JourneyRiskClassifier.classify(QuestionToolCatalog.SPLIT, null)).isEqualTo(JourneyPlan.Risk.LECTURE);
        assertThat(JourneyRiskClassifier.classify(QuestionToolCatalog.TRACK, null)).isEqualTo(JourneyPlan.Risk.LECTURE);
        assertThat(QuestionToolCatalog.isQuestionTool("bash")).isFalse();
        assertThat(QuestionToolCatalog.isQuestionTool(null)).isFalse();
    }
}
