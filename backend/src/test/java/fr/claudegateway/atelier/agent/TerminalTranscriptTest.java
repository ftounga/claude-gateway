package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Appariement commande↔sortie de la transcription persistée (F-30 SF-30-09). Mêmes règles que
 * l'affichage : priorité au {@code toolUseId}, repli sur la dernière commande sans sortie, bloc
 * orphelin en dernier recours — une sortie n'est jamais perdue.
 */
class TerminalTranscriptTest {

    @Test
    void pairsOutputsWithTheirCommandByToolUseIdEvenOutOfOrder() {
        TerminalTranscript transcript = new TerminalTranscript();
        transcript.addCommand("bash", "tu_1", "npm test");
        transcript.addCommand("bash", "tu_2", "npm run build");
        transcript.addOutput("bash", "tu_2", "build ok", false);
        transcript.addOutput("bash", "tu_1", "12 passing", false);

        var blocks = transcript.bounded(10_000).blocks();

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).command()).isEqualTo("npm test");
        assertThat(blocks.get(0).output()).isEqualTo("12 passing");
        assertThat(blocks.get(1).output()).isEqualTo("build ok");
    }

    @Test
    void withoutToolUseIdFallsBackToTheLastCommandWithoutOutput() {
        TerminalTranscript transcript = new TerminalTranscript();
        transcript.addCommand("bash", null, "npm test");
        transcript.addOutput("bash", null, "12 passing", false);

        var blocks = transcript.bounded(10_000).blocks();

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).output()).isEqualTo("12 passing");
        assertThat(blocks.get(0).hasOutput()).isTrue();
    }

    @Test
    void anOutputWithNoMatchingCommandBecomesAnOrphanBlock() {
        TerminalTranscript transcript = new TerminalTranscript();
        transcript.addOutput("bash", "inconnu", "orpheline", false);

        var blocks = transcript.bounded(10_000).blocks();

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).command()).isNull();
        assertThat(blocks.get(0).output()).isEqualTo("orpheline");
    }

    @Test
    void failureIsCarriedOnTheBlock() {
        TerminalTranscript transcript = new TerminalTranscript();
        transcript.addCommand("bash", "tu_1", "npm run build");
        transcript.addOutput("bash", "tu_1", "command not found", true);

        assertThat(transcript.bounded(10_000).blocks().get(0).error()).isTrue();
    }

    @Test
    void boundKeepsWhatFitsAndReportsWhatWasOmitted() {
        // Un tour qui installe un projet entier ne doit pas faire gonfler l'historique sans limite.
        TerminalTranscript transcript = new TerminalTranscript();
        for (int i = 0; i < 5; i++) {
            transcript.addCommand("bash", "tu_" + i, "cmd" + i);
            transcript.addOutput("bash", "tu_" + i, "x".repeat(100), false);
        }

        var bounded = transcript.bounded(250);

        assertThat(bounded.blocks()).hasSize(2);
        assertThat(bounded.omitted()).isEqualTo(3);
    }

    @Test
    void anEmptyTranscriptHasNothingToPersist() {
        assertThat(new TerminalTranscript().isEmpty()).isTrue();
    }
}
