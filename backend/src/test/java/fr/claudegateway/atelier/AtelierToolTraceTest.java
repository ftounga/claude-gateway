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

    // ---------------------------------------- le raisonnement rejoué (F-134 / SF-134-04)

    @Test
    void theReasoningComesBackFirst_beforeTheTextAndTheToolCalls() {
        // L'ORDRE EST LA PROPRIÉTÉ. Le fournisseur a mis en cache « raisonnement, texte, appels » :
        // le rejeu doit rendre exactement cette suite, sinon le ruban diffère dès le premier bloc
        // et tout ce qui suit est réécrit au double du tarif d'entrée.
        AtelierToolTrace trace = new AtelierToolTrace(List.of(new AtelierToolTrace.Step(
                "Je regarde le fichier.",
                List.of(new AtelierToolTrace.Call("c1", "read_file", input("path", "a.txt"),
                        "contenu", false)),
                List.of(new AtelierToolTrace.Thought("", "sig-abc", null)))));

        List<AgentMessage> replayed = trace.replay();

        List<AgentContentBlock> assistant = replayed.get(0).content();
        assertThat(assistant).hasSize(3);
        assertThat(assistant.get(0)).isInstanceOf(AgentContentBlock.Reasoning.class);
        assertThat(assistant.get(1)).isInstanceOf(AgentContentBlock.Text.class);
        assertThat(assistant.get(2)).isInstanceOf(AgentContentBlock.ToolUse.class);
    }

    @Test
    void theSignatureSurvivesIntact() {
        // Le bloc est signé par le fournisseur, qui refuse un bloc retouché : la signature doit
        // traverser la base de données sans une modification.
        AtelierToolTrace trace = new AtelierToolTrace(List.of(new AtelierToolTrace.Step(
                "texte",
                List.of(new AtelierToolTrace.Call("c1", "read_file", input("path", "a"), "ok", false)),
                List.of(new AtelierToolTrace.Thought("réflexion", "SIG-9f3a==", null)))));

        AgentContentBlock first = trace.replay().get(0).content().get(0);

        assertThat(first).isInstanceOf(AgentContentBlock.Reasoning.class);
        AgentContentBlock.Reasoning reasoning = (AgentContentBlock.Reasoning) first;
        assertThat(reasoning.signature()).isEqualTo("SIG-9f3a==");
        assertThat(reasoning.text()).isEqualTo("réflexion");
    }

    @Test
    void aRedactedBlockSurvivesWithoutBeingInterpreted() {
        AtelierToolTrace trace = new AtelierToolTrace(List.of(new AtelierToolTrace.Step(
                "texte",
                List.of(new AtelierToolTrace.Call("c1", "read_file", input("path", "a"), "ok", false)),
                List.of(new AtelierToolTrace.Thought(null, null, "charge-opaque")))));

        AgentContentBlock first = trace.replay().get(0).content().get(0);

        assertThat(first).isInstanceOf(AgentContentBlock.RedactedReasoning.class);
        assertThat(((AgentContentBlock.RedactedReasoning) first).data()).isEqualTo("charge-opaque");
    }

    @Test
    void anEmptyThoughtIsNotReplayed() {
        // Un bloc sans signature ni charge n'apporte rien et pourrait être refusé.
        AtelierToolTrace trace = new AtelierToolTrace(List.of(new AtelierToolTrace.Step(
                "texte",
                List.of(new AtelierToolTrace.Call("c1", "read_file", input("path", "a"), "ok", false)),
                List.of(new AtelierToolTrace.Thought("", null, null),
                        new AtelierToolTrace.Thought(null, "  ", "")))));

        assertThat(trace.replay().get(0).content())
                .noneMatch(block -> block instanceof AgentContentBlock.Reasoning
                        || block instanceof AgentContentBlock.RedactedReasoning);
    }

    @Test
    void aTraceWrittenBeforeThisFeatureReplaysExactlyAsBefore() {
        // RÉTRO-COMPATIBILITÉ. Les traces déjà en base n'ont pas de champ `thoughts` : elles
        // doivent se rejouer comme avant, sans erreur et sans bloc fantôme.
        AtelierToolTrace old = AtelierToolTrace.fromJson("""
                {"steps":[{"text":"texte","calls":[{"id":"c1","name":"read_file",
                 "input":{"path":"a"},"result":"ok","error":false}]}]}
                """);

        List<AgentContentBlock> assistant = old.replay().get(0).content();

        assertThat(assistant).hasSize(2);
        assertThat(assistant.get(0)).isInstanceOf(AgentContentBlock.Text.class);
        assertThat(assistant.get(1)).isInstanceOf(AgentContentBlock.ToolUse.class);
    }

    @Test
    void theTraceSurvivesTheRoundTripThroughJson() {
        // Le vrai trajet : écriture en base, relecture, rejeu. C'est là que se perdrait une
        // signature mal sérialisée.
        AtelierToolTrace before = new AtelierToolTrace(List.of(new AtelierToolTrace.Step(
                "texte",
                List.of(new AtelierToolTrace.Call("c1", "bash", input("cmd", "ls"), "a.txt", false)),
                List.of(new AtelierToolTrace.Thought("", "SIG-round-trip", null)))));

        AtelierToolTrace after = AtelierToolTrace.fromJson(before.toJson());

        AgentContentBlock first = after.replay().get(0).content().get(0);
        assertThat(((AgentContentBlock.Reasoning) first).signature()).isEqualTo("SIG-round-trip");
    }
}
