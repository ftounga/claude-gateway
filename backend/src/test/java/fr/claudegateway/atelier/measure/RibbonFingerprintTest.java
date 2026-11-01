package fr.claudegateway.atelier.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;

/** L'empreinte du ruban (F-229 / SF-229-01) : stable, ordonnée, sans contenu, et sa divergence. */
class RibbonFingerprintTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<AgentMessage> ribbon(String... texts) {
        List<AgentMessage> messages = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            messages.add(new AgentMessage(i % 2 == 0 ? "user" : "assistant",
                    List.of(new AgentContentBlock.Text(texts[i]))));
        }
        return messages;
    }

    @Test
    void theSameRibbonAlwaysGivesTheSameFingerprint() {
        String first = RibbonFingerprint.of(ribbon("bonjour", "salut", "suite")).toJson();
        String second = RibbonFingerprint.of(ribbon("bonjour", "salut", "suite")).toJson();

        assertThat(first).isEqualTo(second);
        assertThat(RibbonFingerprint.parse(first).blocks())
                .allSatisfy(block -> assertThat(block.hash()).matches("[0-9a-f]{16}"))
                .extracting(RibbonFingerprint.Block::size).allSatisfy(size -> assertThat(size).isPositive());
    }

    @Test
    void theFingerprintCarriesNoContent() {
        String json = RibbonFingerprint.of(ribbon("secret-de-conversation", "réponse-confidentielle")).toJson();

        assertThat(json).doesNotContain("secret").doesNotContain("confidentielle").matches("\\[\\[.*\\]\\]");
    }

    @Test
    void theOrderOfBlocksMatters() {
        RibbonFingerprint ab = RibbonFingerprint.of(ribbon("a", "b"));
        RibbonFingerprint ba = RibbonFingerprint.of(ribbon("b", "a"));

        assertThat(ba.divergenceFrom(ab)).isZero();
    }

    @Test
    void aProlongedRibbonDoesNotDiverge() {
        RibbonFingerprint before = RibbonFingerprint.of(ribbon("q1", "r1", "q2"));
        RibbonFingerprint after = RibbonFingerprint.of(ribbon("q1", "r1", "q2", "r2", "q3"));

        assertThat(after.divergenceFrom(before)).isEqualTo(-1);
    }

    @Test
    void theFirstDifferentBlockIsTheDivergenceAndWhatFollowsIsLost() {
        RibbonFingerprint before = RibbonFingerprint.of(ribbon("q1", "r1", "q2"));
        RibbonFingerprint after = RibbonFingerprint.of(ribbon("q1", "r1-autre", "q2", "r2"));

        assertThat(after.divergenceFrom(before)).isEqualTo(1);
        assertThat(before.charsFrom(1)).isEqualTo(before.blocks().get(1).size() + before.blocks().get(2).size());
    }

    @Test
    void aShorterRibbonDivergesAtItsEnd() {
        RibbonFingerprint before = RibbonFingerprint.of(ribbon("q1", "r1", "q2"));
        RibbonFingerprint after = RibbonFingerprint.of(ribbon("q1"));

        assertThat(after.divergenceFrom(before)).isEqualTo(1);
    }

    @Test
    void effortDirectivesAndMergedSameRoleMessagesDoNotChangeTheRibbon() {
        List<AgentMessage> plain = List.of(new AgentMessage("user",
                List.of(new AgentContentBlock.Text("a"), new AgentContentBlock.Text("b"))));
        List<AgentMessage> split = List.of(AgentMessage.userText("a"), AgentMessage.effort("low"),
                AgentMessage.userText("b"));

        assertThat(RibbonFingerprint.of(split).toJson()).isEqualTo(RibbonFingerprint.of(plain).toJson());
    }

    @Test
    void everyBlockKindIsFingerprintedAndATruncatedResultIsRecognised() throws Exception {
        List<AgentMessage> messages = List.of(AgentMessage.userText("go"),
                AgentMessage.assistant(List.of(new AgentContentBlock.Reasoning("", "sig"),
                        new AgentContentBlock.ToolUse("t1", "bash", MAPPER.readTree("{\"command\":\"ls\"}")))),
                AgentMessage.toolResults(List.of(new AgentContentBlock.ToolResult("t1",
                        "x".repeat(10) + "\n… (fin tronquée)", false))));

        RibbonFingerprint fingerprint = RibbonFingerprint.of(messages);

        assertThat(fingerprint.size()).isEqualTo(4);
        assertThat(fingerprint.isTruncatedResult(3)).isTrue();
        assertThat(fingerprint.isTruncatedResult(2)).isFalse();
    }

    @Test
    void aRibbonBeyondTheCapIsNotStored() {
        List<AgentMessage> messages = new ArrayList<>();
        List<AgentContentBlock> blocks = new ArrayList<>();
        for (int i = 0; i <= RibbonFingerprint.MAX_BLOCKS; i++) {
            blocks.add(new AgentContentBlock.Text("b" + i));
        }
        messages.add(new AgentMessage("user", blocks));

        RibbonFingerprint fingerprint = RibbonFingerprint.of(messages);

        assertThat(fingerprint.tooLarge()).isTrue();
        assertThat(fingerprint.toJson()).isNull();
    }

    @Test
    void anUnreadableStoredFingerprintIsNoFingerprint() {
        assertThat(RibbonFingerprint.parse(null)).isNull();
        assertThat(RibbonFingerprint.parse("pas du json")).isNull();
        assertThat(RibbonFingerprint.parse("{\"a\":1}")).isNull();
        assertThat(RibbonFingerprint.parse("[[\"texte libre\",3]]")).isNull();
        assertThat(RibbonFingerprint.parse("[[\"0123456789abcdef\",-1]]")).isNull();
        assertThat(RibbonFingerprint.parse("[[\"0123456789abcdef\",3]]").size()).isEqualTo(1);
    }

    @Test
    void theCallerListIsNeverModified() {
        List<AgentMessage> messages = new ArrayList<>(List.of(new AgentMessage("assistant",
                List.of(new AgentContentBlock.Text("tête"))), AgentMessage.userText("q")));
        List<AgentMessage> copy = List.copyOf(messages);

        RibbonFingerprint.of(messages);

        assertThat(messages).isEqualTo(copy);
    }
}
