package fr.claudegateway.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Le mode du tour porté par {@link AgentTurnRequest} (F-120 / SF-120-02) : un mode absent vaut
 * {@link AgentTurnMode#ACT}, de sorte qu'un appelant d'avant SF-120-02 obtient le comportement
 * historique sans rien changer.
 */
class AgentTurnModeTest {

    @Test
    void anAbsentModeDefaultsToAct() {
        // Constructeur historique (5 arguments) : aucun mode fourni ⇒ ACT.
        AgentTurnRequest legacy =
                new AgentTurnRequest("m", "s", List.of(), List.of(), null);
        assertThat(legacy.mode()).isEqualTo(AgentTurnMode.ACT);
    }

    @Test
    void aNullModeIsNormalisedToAct() {
        AgentTurnRequest nullMode = new AgentTurnRequest("m", "s", List.of(), List.of(), null,
                AgentReasoning.none(), AgentContextPolicy.none(), null);
        assertThat(nullMode.mode()).isEqualTo(AgentTurnMode.ACT);
    }

    @Test
    void anExplicitModeIsKept() {
        AgentTurnRequest planned = new AgentTurnRequest("m", "s", List.of(), List.of(), null,
                AgentReasoning.none(), AgentContextPolicy.none(), AgentTurnMode.ANSWER_PLAN);
        assertThat(planned.mode()).isEqualTo(AgentTurnMode.ANSWER_PLAN);
    }
}
