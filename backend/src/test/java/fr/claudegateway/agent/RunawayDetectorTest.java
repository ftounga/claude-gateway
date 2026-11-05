package fr.claudegateway.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Détecteur d'emballement (F-172 / SF-172-07) sur des flux simulés : seuil 16 000 jetons, débit
 * 150 jetons/s, soit ≈ 107 s de raisonnement caché sans rien de visible.
 */
class RunawayDetectorTest {

    private static final int THRESHOLD = 16_000;
    private static final int RATE = 150;

    private RunawayDetector startedAt(long start) {
        RunawayDetector detector = new RunawayDetector(THRESHOLD, RATE, start);
        detector.messageStarted(start);
        detector.blockStarted("thinking", "");
        return detector;
    }

    @Test
    void aPureRunawayIsCaughtPastTheThreshold() {
        RunawayDetector detector = startedAt(0L);
        detector.reasoning("");
        detector.otherEvent();

        // 100 s × 150 = 15 000 : sous le seuil.
        assertThat(detector.runaway(100_000L)).isFalse();
        // 110 s × 150 = 16 500 : au-delà.
        assertThat(detector.runaway(110_000L)).isTrue();
        assertThat(detector.producedTokens(110_000L)).isEqualTo(16_500);
        assertThat(detector.diagnostic(110_000L))
                .contains("blocs=[thinking]", "texte_car=0", "outil_en_cours=aucun", "jetons_sortie=16500");
    }

    @Test
    void aLongLegitimateReasoningFollowedByTextIsNeverCut() {
        RunawayDetector detector = startedAt(0L);
        // 90 s de raisonnement caché (13 500 jetons estimés), puis la réponse commence.
        assertThat(detector.runaway(90_000L)).isFalse();
        detector.blockStarted("text", "");
        detector.text("Voici");

        // Dès la première activité visible, l'appel n'est plus jamais coupé — même très long.
        assertThat(detector.visible()).isTrue();
        assertThat(detector.runaway(600_000L)).isFalse();
    }

    @Test
    void aReadableProgressNoteCountsAsVisible() {
        RunawayDetector detector = startedAt(0L);
        detector.reasoning("Je lis le fichier de configuration.");

        assertThat(detector.runaway(600_000L)).isFalse();
    }

    @Test
    void aToolBeingWrittenIsNeverCut() {
        RunawayDetector detector = startedAt(0L);
        detector.blockStopped();
        detector.blockStarted("tool_use", "write_file");
        detector.toolInput("{\"path\":\"a.txt\",\"content\":\"");

        assertThat(detector.runaway(600_000L)).isFalse();
        assertThat(detector.diagnostic(600_000L)).contains("outil_en_cours=write_file(");
    }

    @Test
    void aServerToolCountsAsVisible() {
        RunawayDetector detector = startedAt(0L);
        detector.blockStarted("server_tool_use", "web_search");

        assertThat(detector.runaway(600_000L)).isFalse();
    }

    @Test
    void aReportedOutputBeyondTheEstimateWins() {
        RunawayDetector detector = startedAt(0L);
        detector.reportedOutput(20_000);

        // 10 s ne font que 1 500 jetons estimés, mais le fournisseur en rapporte 20 000.
        assertThat(detector.producedTokens(10_000L)).isEqualTo(20_000);
        assertThat(detector.runaway(10_000L)).isTrue();
    }

    @Test
    void timeIsCountedFromTheStartOfTheMessage() {
        RunawayDetector detector = new RunawayDetector(THRESHOLD, RATE, 0L);
        // Le message s'annonce après 60 s d'attente (lecture de 190 k jetons d'entrée) : ce délai
        // n'est pas de la sortie.
        detector.messageStarted(60_000L);
        detector.blockStarted("thinking", "");

        assertThat(detector.runaway(160_000L)).isFalse();
        assertThat(detector.runaway(170_000L)).isTrue();
    }

    @Test
    void anEmptyTextDeltaIsNotVisible() {
        RunawayDetector detector = startedAt(0L);
        detector.text("");

        assertThat(detector.runaway(110_000L)).isTrue();
    }
}
