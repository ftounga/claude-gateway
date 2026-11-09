package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;

/** F-253 / SF-253-06 — la cause d'un tour vide : arrêt, outils, motifs de rejet, sans contenu. */
class JarvisFastBrainDiagnosticTest {

    private static AgentTurn turn(boolean truncated, List<AgentToolCall> tools) {
        AgentTurn t = org.mockito.Mockito.mock(AgentTurn.class);
        org.mockito.Mockito.when(t.truncated()).thenReturn(truncated);
        org.mockito.Mockito.when(t.toolCalls()).thenReturn(tools);
        return t;
    }

    @Test
    void saysTheStopAndThatNothingWasRejected() {
        assertThat(JarvisFastBrainService.emptyDiagnostic(turn(true, List.of()), JarvisFastBrainService.Guards.NONE))
                .isEqualTo("stop=max_tokens outils=aucun rejets=aucun");
        assertThat(JarvisFastBrainService.emptyDiagnostic(turn(false, List.of()), JarvisFastBrainService.Guards.NONE))
                .isEqualTo("stop=end_turn outils=aucun rejets=aucun");
        assertThat(JarvisFastBrainService.emptyDiagnostic(null, JarvisFastBrainService.Guards.NONE))
                .startsWith("stop=aucun");
    }

    @Test
    void namesEachToolAndEachRejectionMotive() {
        AgentToolCall tool = org.mockito.Mockito.mock(AgentToolCall.class);
        org.mockito.Mockito.when(tool.name()).thenReturn("afficher");
        JarvisFastBrainService.Guards guards = new JarvisFastBrainService.Guards(1, 2, 0, false, true, 0, 0, null, 0, List.of());
        String out = JarvisFastBrainService.emptyDiagnostic(turn(false, List.of(tool)), guards);
        assertThat(out).isEqualTo("stop=tool_use outils=afficher rejets=ancrage:1,remplissage:2,texte_outil:1");
        assertThat(out).matches("[a-z0-9_=:, ]{1,200}");
    }
}
