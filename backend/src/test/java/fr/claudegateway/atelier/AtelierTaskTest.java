package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;

/**
 * Sous-boucle {@code task} (F-150 / SF-150-02), en isolation : elle exécute les outils, agrège la
 * consommation, respecte le stop partagé, et rend une synthèse bornée.
 */
class AtelierTaskTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<AgentTool> TOOLS =
            List.of(new AgentTool("write_file", "écrit", java.util.Map.of("type", "object")));

    @Test
    void runsTheToolThenReturnsTheSynthesisAndAggregatesCost() {
        AtomicInteger executed = new AtomicInteger();
        AiAgentProvider provider = new AiAgentProvider() {
            private int calls = 0;
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request) {
                if (calls++ == 0) {
                    List<AgentToolCall> tc = new ArrayList<>();
                    tc.add(new AgentToolCall("c0", "write_file", MAPPER.createObjectNode().put("path", "a")));
                    return new AgentTurn("", tc, false, 10, 4);
                }
                return new AgentTurn("Fait : a écrit.", List.of(), true, 3, 2);
            }
        };

        AtelierTask.Result result = AtelierTask.run(provider, "m", null, "écris a", null, TOOLS, false,
                call -> {
                    executed.incrementAndGet();
                    return new AtelierTask.ExecutedTool("ok", false);
                },
                () -> false, AgentReasoning.none());

        assertThat(executed.get()).isEqualTo(1);
        assertThat(result.answer()).isEqualTo("Fait : a écrit.");
        // Coût agrégé sur les deux tours (10+3 en entrée, 4+2 en sortie).
        assertThat(result.inputTokens()).isEqualTo(13);
        assertThat(result.outputTokens()).isEqualTo(6);
    }

    @Test
    void stopHaltsTheLoopBeforeCallingTheProvider() {
        AtomicInteger providerCalls = new AtomicInteger();
        AiAgentProvider provider = request -> {
            providerCalls.incrementAndGet();
            return new AgentTurn("jamais", List.of(), true, 1, 1);
        };

        AtelierTask.Result result = AtelierTask.run(provider, "m", null, "écris a", null, TOOLS, false,
                call -> new AtelierTask.ExecutedTool("ok", false),
                () -> true, AgentReasoning.none());

        assertThat(providerCalls.get()).isZero();
        assertThat(result.answer()).contains("interrompue");
    }

    @Test
    void aTooLongSynthesisIsTruncated() {
        String huge = "x".repeat(AtelierTask.MAX_ANSWER_CHARS + 500);
        AiAgentProvider provider = request -> new AgentTurn(huge, List.of(), true, 1, 1);

        AtelierTask.Result result = AtelierTask.run(provider, "m", null, "p", null, TOOLS, false,
                call -> new AtelierTask.ExecutedTool("ok", false),
                () -> false, AgentReasoning.none());

        assertThat(result.answer()).hasSizeLessThanOrEqualTo(AtelierTask.MAX_ANSWER_CHARS + 40);
        assertThat(result.answer()).endsWith("(synthèse tronquée)");
    }

    @Test
    void theReadOnlySubTaskIsToldItCannotWriteAndIsNeverGivenAWorktreeStory() {
        // F-121 / SF-121-14 : en lecture seule, la consigne système dit la VÉRITÉ de la panoplie —
        // ni écriture, ni commande, et aucun worktree (rien n'écrit, il n'y a rien à isoler).
        java.util.concurrent.atomic.AtomicReference<String> system =
                new java.util.concurrent.atomic.AtomicReference<>();
        AiAgentProvider provider = request -> {
            system.set(request.system());
            return new AgentTurn("Constat : rien à signaler.", List.of(), true, 1, 1);
        };

        AtelierTask.Result result = AtelierTask.run(provider, "m", null, "audite a", null, TOOLS, true,
                call -> new AtelierTask.ExecutedTool("ok", false),
                () -> false, AgentReasoning.none());

        assertThat(result.answer()).isEqualTo("Constat : rien à signaler.");
        assertThat(system.get()).contains("EN LECTURE SEULE");
        assertThat(system.get()).contains("ni écrire, ni éditer, ni exécuter");
        assertThat(system.get()).doesNotContain("worktree");
        assertThat(system.get()).doesNotContain("panoplie complète");
    }

    @Test
    void theWritingSubTaskKeepsItsOwnSystemPrompt() {
        // Sans le drapeau, la consigne historique (worktree isolé, panoplie complète) est intacte.
        java.util.concurrent.atomic.AtomicReference<String> system =
                new java.util.concurrent.atomic.AtomicReference<>();
        AiAgentProvider provider = request -> {
            system.set(request.system());
            return new AgentTurn("Fait.", List.of(), true, 1, 1);
        };

        AtelierTask.run(provider, "m", null, "écris a", null, TOOLS, false,
                call -> new AtelierTask.ExecutedTool("ok", false),
                () -> false, AgentReasoning.none());

        assertThat(system.get()).contains("worktree git ISOLÉ");
        assertThat(system.get()).contains("panoplie complète");
    }
}
