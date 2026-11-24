package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;

/**
 * F-276 / SF-276-02 — le ruban envoyé fait l'aller-retour sans perte, et le rejeu le substitue aux lignes
 * de son tour sans rien changer aux autres.
 */
class AtelierSentRibbonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<AgentMessage> everyKindOfBlock() throws Exception {
        return List.of(
                AgentMessage.effort("medium"),
                AgentMessage.userText("[Contexte du sujet]\nconsigne augmentée\n\nla parole"),
                AgentMessage.assistant(List.of(
                        new AgentContentBlock.Reasoning("je pense", "sig-1"),
                        new AgentContentBlock.Reasoning("", null),
                        new AgentContentBlock.RedactedReasoning("opaque"),
                        new AgentContentBlock.ServerTool(MAPPER.readTree(
                                "{\"type\":\"server_tool_use\",\"id\":\"s1\",\"name\":\"web_search\",\"input\":{\"q\":\"x\"}}")),
                        new AgentContentBlock.Text("je regarde"),
                        new AgentContentBlock.ToolUse("t1", "bash", MAPPER.readTree("{\"command\":\"ls -la\",\"n\":3}")),
                        new AgentContentBlock.ToolUse("t2", "noop", MissingNode.getInstance()))),
                AgentMessage.toolResults(List.of(
                        new AgentContentBlock.ToolResult("t1", "x".repeat(20_000), false),
                        new AgentContentBlock.ToolResult("t2", "capture", true, List.of(
                                new AgentContentBlock.Text("capture"),
                                new AgentContentBlock.Image("image/png", "iVBORw0KGgo="))),
                        new AgentContentBlock.Text("[Précision de l'utilisateur]"))),
                new AgentMessage("user", List.of(new AgentContentBlock.Document("application/pdf", "JVBERi0="))),
                AgentMessage.assistant(List.of(new AgentContentBlock.Reasoning("fin", "sig-2"),
                        new AgentContentBlock.Text("Voilà."))));
    }

    @Test
    void roundTripsEveryBlockWithoutLoss() throws Exception {
        UUID from = UUID.randomUUID();
        UUID steer = UUID.randomUUID();
        AtelierSentRibbon ribbon = new AtelierSentRibbon(from, List.of(steer), everyKindOfBlock());

        AtelierSentRibbon back = AtelierSentRibbon.parse(ribbon.toJson());

        assertThat(back).isNotNull();
        assertThat(back.from()).isEqualTo(from);
        assertThat(back.rows()).containsExactly(steer);
        assertThat(back.messages()).isEqualTo(ribbon.messages());
        assertThat(back.toJson()).isEqualTo(ribbon.toJson());
    }

    @Test
    void anOversizedOrEmptyRibbonIsNotStored() {
        UUID from = UUID.randomUUID();
        assertThat(new AtelierSentRibbon(from, List.of(),
                List.of(AgentMessage.userText("y".repeat(AtelierSentRibbon.MAX_CHARS)))).toJson()).isNull();
        assertThat(new AtelierSentRibbon(from, List.of(), List.of()).toJson()).isNull();
        assertThat(new AtelierSentRibbon(null, List.of(), List.of(AgentMessage.userText("a"))).toJson()).isNull();
    }

    @Test
    void anUnreadableRibbonIsIgnored() {
        assertThat(AtelierSentRibbon.parse(null)).isNull();
        assertThat(AtelierSentRibbon.parse("pas du json")).isNull();
        assertThat(AtelierSentRibbon.parse("{\"v\":2,\"from\":\"" + UUID.randomUUID() + "\",\"messages\":[]}")).isNull();
        assertThat(AtelierSentRibbon.parse("{\"v\":1,\"from\":\"" + UUID.randomUUID()
                + "\",\"messages\":[{\"role\":\"user\",\"content\":[{\"t\":\"inconnu\"}]}]}")).isNull();
    }

    // ------------------------------------------------------------------ rejeu

    private static AtelierMessage row(String role, String content) {
        AtelierMessage message = AtelierMessage.builder().role(role).content(content).build();
        message.setId(UUID.randomUUID());
        return message;
    }

    @Test
    void aTracedTurnWithARibbonIsReplayedAsSent() {
        AtelierMessage q1 = row("USER", "la parole");
        AtelierMessage steer = row("USER", "précision");
        AtelierMessage foreign = row("USER", "ligne d'un autre flux");
        AtelierMessage a1 = row("ASSISTANT", "réponse retenue");
        AtelierMessage q2 = row("USER", "suite");
        AtelierMessage a2 = row("ASSISTANT", "réponse 2");
        List<AgentMessage> sent = List.of(AgentMessage.userText("consigne augmentée\n\nla parole"),
                AgentMessage.assistant(List.of(new AgentContentBlock.Reasoning("p", "sig"),
                        new AgentContentBlock.Text("dernière réponse réelle"))));
        AtelierSentRibbon ribbon = new AtelierSentRibbon(q1.getId(), List.of(steer.getId()), sent);

        List<AgentMessage> replay = AtelierChatService.replayableHistory(
                List.of(q1, steer, foreign, a1, q2, a2), 12, null, Map.of(a1.getId(), ribbon));

        assertThat(replay.subList(0, 2)).isEqualTo(sent);
        // La ligne étrangère suit le ruban ; le tour sans ruban est rejoué comme avant.
        assertThat(replay.get(2)).isEqualTo(new AgentMessage("user",
                List.of(new AgentContentBlock.Text("ligne d'un autre flux"))));
        assertThat(replay.subList(3, replay.size())).isEqualTo(
                AtelierChatService.replayableHistory(List.of(q2, a2), 12, null));
    }

    @Test
    void withoutRibbonsOrOutsideTheWindowTheReplayIsUnchanged() {
        List<AtelierMessage> past = new java.util.ArrayList<>();
        java.util.Map<UUID, AtelierSentRibbon> ribbons = new java.util.HashMap<>();
        for (int i = 0; i < 14; i++) {
            AtelierMessage q = row("USER", "q" + i);
            AtelierMessage a = row("ASSISTANT", "a" + i);
            past.add(q);
            past.add(a);
            ribbons.put(a.getId(), new AtelierSentRibbon(q.getId(), List.of(),
                    List.of(AgentMessage.userText("augmentée " + i), AgentMessage.assistant(
                            List.of(new AgentContentBlock.Text("réelle " + i))))));
        }
        List<AgentMessage> legacy = AtelierChatService.replayableHistory(past, 2, null);

        assertThat(AtelierChatService.replayableHistory(past, 2, null, Map.of())).isEqualTo(legacy);
        List<AgentMessage> withRibbons = AtelierChatService.replayableHistory(past, 2, null, ribbons);
        int traceFrom = AtelierChatService.firstTracedIndex(past, 2);
        // Les tours hors fenêtre restent en texte seul ; ceux de la fenêtre partent tels qu'envoyés.
        assertThat(withRibbons.subList(0, traceFrom - 1)).isEqualTo(legacy.subList(0, traceFrom - 1));
        assertThat(withRibbons.get(withRibbons.size() - 1)).isEqualTo(
                AgentMessage.assistant(List.of(new AgentContentBlock.Text("réelle 13"))));
    }

    @Test
    void aRibbonWhoseOpeningRowIsMissingFallsBackToTheReplayOfBefore() {
        AtelierMessage q1 = row("USER", "q");
        AtelierMessage a1 = row("ASSISTANT", "a");
        AtelierSentRibbon orphan = new AtelierSentRibbon(UUID.randomUUID(), List.of(),
                List.of(AgentMessage.userText("x"), AgentMessage.assistant(List.of(new AgentContentBlock.Text("y")))));

        assertThat(AtelierChatService.replayableHistory(List.of(q1, a1), 12, null, Map.of(a1.getId(), orphan)))
                .isEqualTo(AtelierChatService.replayableHistory(List.of(q1, a1), 12, null));
    }

    // ------------------------------------------------------------------ rangement

    @Test
    void theRibbonCarriesTheTurnAndItsRealLastAnswer() {
        UUID from = UUID.randomUUID();
        List<AgentMessage> messages = List.of(AgentMessage.userText("histoire"),
                AgentMessage.assistant(List.of(new AgentContentBlock.Text("avant"))),
                AgentMessage.effort("high"), AgentMessage.userText("consigne augmentée"));
        List<AgentContentBlock> answer = List.of(new AgentContentBlock.Reasoning("r", "s"),
                new AgentContentBlock.Text("réponse réelle"));

        AtelierSentRibbon ribbon = AtelierSentRibbon.parse(
                AtelierChatService.sentRibbonJson(false, messages, 2, answer, false, "texte retenu", from, List.of()));

        assertThat(ribbon.messages()).containsExactly(AgentMessage.effort("high"),
                AgentMessage.userText("consigne augmentée"), AgentMessage.assistant(answer));
    }

    @Test
    void aBrokenOrUnfinishedTurnIsNotStored() {
        UUID from = UUID.randomUUID();
        List<AgentMessage> messages = List.of(AgentMessage.userText("consigne"));
        List<AgentContentBlock> answer = List.of(new AgentContentBlock.Text("r"));

        assertThat(AtelierChatService.sentRibbonJson(true, messages, 0, answer, false, "t", from, List.of())).isNull();
        // Réponse coupée, refus, synthèse forcée : ni réponse finale, ni frontière d'étape.
        assertThat(AtelierChatService.sentRibbonJson(false, messages, 0, null, false, "t", from, List.of())).isNull();
        // Interruption : clos par le texte du tour, comme le rejeu d'avant.
        assertThat(AtelierSentRibbon.parse(AtelierChatService.sentRibbonJson(false, messages, 0, null, true,
                "Interrompu.", from, List.of())).messages()).last().isEqualTo(
                        AgentMessage.assistant(List.of(new AgentContentBlock.Text("Interrompu."))));
    }

    @Test
    void theEffortInForceIsTheLastReplayedDirective() {
        assertThat(AtelierChatService.lastReplayedEffort(List.of(AgentMessage.userText("a")), "high"))
                .isEqualTo("high");
        assertThat(AtelierChatService.lastReplayedEffort(List.of(AgentMessage.effort("low"),
                AgentMessage.userText("a"), AgentMessage.effort("medium")), "high")).isEqualTo("medium");
    }
}
