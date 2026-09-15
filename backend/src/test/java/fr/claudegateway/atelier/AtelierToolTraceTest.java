package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;

/**
 * Trajectoire d'outils d'un tour (F-39 / SF-39-03) : ce qui est retenu, ce qui est coupé, et ce
 * qui repart chez le fournisseur. Les bornes ne sont pas décoratives — un historique non borné
 * finit par coûter plus cher que le travail lui-même.
 */
class AtelierToolTraceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode input(String key, String value) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(key, value);
        return node;
    }

    private static AtelierToolTrace oneCall(String result) {
        return new AtelierToolTrace(List.of(new AtelierToolTrace.Step("je regarde",
                List.of(new AtelierToolTrace.Call("call_1", "bash", input("command", "ls"), result, false)))));
    }

    @Test
    void traceSurvivesAJsonRoundTrip() {
        AtelierToolTrace trace = oneCall("fichier.txt");

        AtelierToolTrace reread = AtelierToolTrace.fromJson(trace.toJson());

        assertThat(reread.steps()).hasSize(1);
        AtelierToolTrace.Call call = reread.steps().get(0).calls().get(0);
        assertThat(call.name()).isEqualTo("bash");
        assertThat(call.input().path("command").asText()).isEqualTo("ls");
        assertThat(call.result()).isEqualTo("fichier.txt");
        assertThat(call.error()).isFalse();
    }

    @Test
    void anEmptyTraceSerializesToNothingAtAll() {
        assertThat(AtelierToolTrace.empty().toJson()).isNull();
        assertThat(new AtelierToolTrace(List.of()).isEmpty()).isTrue();
    }

    @Test
    void anOversizedResultKeepsItsHeadToMatchTheLiveView() {
        // F-119 / SF-119-03 : le rejeu garde désormais la TÊTE — le même extrait que l'affichage en
        // direct (bashOutcome/readOutcome gardent le début) — pour que la mémoire d'un même résultat
        // ne bascule pas d'un tour à l'autre. La coupe est marquée en fin.
        String output = "DÉBUT DE LA SORTIE\n" + "bruit".repeat(4_000);

        String bounded = AtelierToolTrace.boundResult(output);

        assertThat(bounded).startsWith("DÉBUT DE LA SORTIE");
        assertThat(bounded).endsWith(AtelierToolTrace.TRUNCATION_MARK);
        assertThat(bounded).hasSize(AtelierToolTrace.MAX_RESULT_CHARS + AtelierToolTrace.TRUNCATION_MARK.length());
    }

    @Test
    void aShortResultIsKeptVerbatim() {
        assertThat(AtelierToolTrace.boundResult("ok")).isEqualTo("ok");
        assertThat(AtelierToolTrace.boundResult(null)).isEmpty();
    }

    @Test
    void anOversizedTraceDropsItsOldestStepsAndKeepsTheRecentOnes() {
        List<AtelierToolTrace.Step> steps = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            steps.add(new AtelierToolTrace.Step("étape " + i, List.of(new AtelierToolTrace.Call(
                    "call_" + i, "bash", input("command", "c" + i), "x".repeat(3_000), false))));
        }

        String json = new AtelierToolTrace(steps).toJson();

        assertThat(json).isNotNull();
        assertThat(json.length()).isLessThanOrEqualTo(AtelierToolTrace.MAX_TRACE_CHARS);
        assertThat(json).contains("\"call_39\"");
        assertThat(json).doesNotContain("\"call_0\"");
    }

    @Test
    void unreadableJsonYieldsAnEmptyTraceRatherThanAnException() {
        assertThat(AtelierToolTrace.fromJson("{ceci n'est pas du json").isEmpty()).isTrue();
        assertThat(AtelierToolTrace.fromJson(null).isEmpty()).isTrue();
        assertThat(AtelierToolTrace.fromJson("   ").isEmpty()).isTrue();
    }

    @Test
    void replayPairsEveryToolUseWithItsResult() {
        List<AgentMessage> messages = oneCall("fichier.txt").replay();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("assistant");
        assertThat(messages.get(0).content()).element(0).isInstanceOf(AgentContentBlock.Text.class);
        AgentContentBlock.ToolUse use = (AgentContentBlock.ToolUse) messages.get(0).content().get(1);
        assertThat(use.id()).isEqualTo("call_1");
        assertThat(messages.get(1).role()).isEqualTo("user");
        AgentContentBlock.ToolResult result = (AgentContentBlock.ToolResult) messages.get(1).content().get(0);
        assertThat(result.toolUseId()).isEqualTo("call_1");
        assertThat(result.content()).isEqualTo("fichier.txt");
    }

    @Test
    void aStepWithoutUsableCallIsDroppedRatherThanReplayedOrphaned() {
        AtelierToolTrace trace = new AtelierToolTrace(List.of(
                new AtelierToolTrace.Step("commentaire seul", List.of()),
                new AtelierToolTrace.Step("sans id", List.of(
                        new AtelierToolTrace.Call(null, "bash", null, "sortie", false)))));

        assertThat(trace.replay()).isEmpty();
    }

    @Test
    void anEmptyResultIsReplayedAsAnExplicitPlaceholder() {
        List<AgentMessage> messages = oneCall("").replay();

        AgentContentBlock.ToolResult result = (AgentContentBlock.ToolResult) messages.get(1).content().get(0);
        assertThat(result.content()).isEqualTo("(vide)");
    }
}
