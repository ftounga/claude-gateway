package fr.claudegateway.atelier.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.atelier.measure.RibbonDivergence.Cause;
import fr.claudegateway.atelier.measure.RibbonDivergence.Signals;

/** La classification de la divergence (F-229 / SF-229-01) : un cas par cause. */
class RibbonDivergenceTest {

    /** Ruban précédent : consigne (0), appel (1), résultat (2). Le tour précédent commence au bloc 0. */
    private static final RibbonFingerprint PREVIOUS = RibbonFingerprint.of(List.of(AgentMessage.userText("q1"),
            AgentMessage.assistant(List.of(new AgentContentBlock.Text("je lis"))),
            AgentMessage.toolResults(List.of(new AgentContentBlock.ToolResult("t1", "sortie entière", false)))));

    private static RibbonFingerprint currentWithResult(String result) {
        List<AgentMessage> messages = new ArrayList<>(List.of(AgentMessage.userText("q1"),
                AgentMessage.assistant(List.of(new AgentContentBlock.Text("je lis"))),
                AgentMessage.toolResults(List.of(new AgentContentBlock.ToolResult("t1", result, false)))));
        messages.add(AgentMessage.assistant(List.of(new AgentContentBlock.Text("fini"))));
        messages.add(AgentMessage.userText("q2"));
        return RibbonFingerprint.of(messages);
    }

    private static final RibbonFingerprint CHANGED_AT_2 = currentWithResult("autre sortie");

    private static Signals signals(boolean steps, boolean reasoning, boolean edited, boolean summary,
            boolean window) {
        return new Signals(0, steps, reasoning, edited, summary, window);
    }

    @Test
    void noPreviousFingerprint() {
        RibbonDivergence d = RibbonDivergence.compare(null, CHANGED_AT_2, Signals.none());

        assertThat(d.cause()).isEqualTo(Cause.NO_PREVIOUS);
        assertThat(d.index()).isNull();
        assertThat(d.lostChars()).isNull();
    }

    @Test
    void aProlongedRibbonHasNoCause() {
        RibbonDivergence d = RibbonDivergence.compare(PREVIOUS, currentWithResult("sortie entière"), Signals.none());

        assertThat(d.index()).isEqualTo(-1);
        assertThat(d.lostChars()).isZero();
        assertThat(d.cause()).isNull();
    }

    @Test
    void resultTruncated() {
        RibbonDivergence d = RibbonDivergence.compare(PREVIOUS,
                currentWithResult("sortie\n… (fin tronquée)"), signals(true, true, true, true, true));

        assertThat(d.index()).isEqualTo(2);
        assertThat(d.lostChars()).isEqualTo(PREVIOUS.blocks().get(2).size());
        assertThat(d.cause()).isEqualTo(Cause.RESULT_TRUNCATED);
    }

    @Test
    void traceStepsDropped() {
        assertThat(RibbonDivergence.compare(PREVIOUS, CHANGED_AT_2, signals(true, true, true, false, false)).cause())
                .isEqualTo(Cause.TRACE_STEPS_DROPPED);
    }

    @Test
    void reasoningDropped() {
        assertThat(RibbonDivergence.compare(PREVIOUS, CHANGED_AT_2, signals(false, true, true, false, false)).cause())
                .isEqualTo(Cause.REASONING_DROPPED);
    }

    @Test
    void clearedInTurn() {
        assertThat(RibbonDivergence.compare(PREVIOUS, CHANGED_AT_2, signals(false, false, true, true, true)).cause())
                .isEqualTo(Cause.CLEARED_IN_TURN);
        // Le ruban est prolongé, mais le fournisseur a mis en cache une version écartée : c'est la cause.
        assertThat(RibbonDivergence.compare(PREVIOUS, currentWithResult("sortie entière"),
                signals(false, false, true, false, false)).cause()).isEqualTo(Cause.CLEARED_IN_TURN);
    }

    @Test
    void compaction() {
        RibbonFingerprint summarised = RibbonFingerprint.of(List.of(AgentMessage.userText("[Résumé] …"),
                AgentMessage.assistant(List.of(new AgentContentBlock.Text("ok"))), AgentMessage.userText("q2")));

        RibbonDivergence d = RibbonDivergence.compare(PREVIOUS, summarised, signals(false, false, false, true, true));

        assertThat(d.index()).isZero();
        assertThat(d.lostChars()).isEqualTo(PREVIOUS.charsFrom(0));
        assertThat(d.cause()).isEqualTo(Cause.COMPACTION);
    }

    @Test
    void windowMoved() {
        assertThat(RibbonDivergence.compare(PREVIOUS, CHANGED_AT_2, signals(false, false, false, false, true)).cause())
                .isEqualTo(Cause.WINDOW_MOVED);
    }

    @Test
    void other() {
        assertThat(RibbonDivergence.compare(PREVIOUS, CHANGED_AT_2, Signals.none()).cause()).isEqualTo(Cause.OTHER);
    }

    @Test
    void factsOfThePreviousTurnDoNotExplainADivergenceBeforeIt() {
        // Divergence à la consigne même du tour précédent (bloc 0) : ni ses étapes ni son raisonnement
        // n'y sont pour rien.
        RibbonFingerprint changedConsigne = RibbonFingerprint.of(List.of(AgentMessage.userText("q1 sans sa note")));

        assertThat(RibbonDivergence.compare(PREVIOUS, changedConsigne,
                new Signals(0, true, true, false, false, false)).cause()).isEqualTo(Cause.OTHER);
    }

    @Test
    void aCurrentRibbonBeyondTheCapIsOther() {
        List<AgentContentBlock> blocks = new ArrayList<>();
        for (int i = 0; i <= RibbonFingerprint.MAX_BLOCKS; i++) {
            blocks.add(new AgentContentBlock.Text("b" + i));
        }
        RibbonDivergence d = RibbonDivergence.compare(PREVIOUS,
                RibbonFingerprint.of(List.of(new AgentMessage("user", blocks))), Signals.none());

        assertThat(d.cause()).isEqualTo(Cause.OTHER);
        assertThat(d.index()).isNull();
    }
}
