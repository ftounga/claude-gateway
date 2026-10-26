package fr.claudegateway.videos;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** <b>Le calcul du devis</b> (F-216 / SF-216-01) : valeurs connues, arrondis, défauts de configuration. */
class VideoQuoteCalculatorTest {

    private static final VideoCostProperties DEFAULTS = VideoCostProperties.defaults();

    private static VideoQuoteCalculator.Rates rates(String turnCost) {
        return new VideoQuoteCalculator.Rates(new BigDecimal("0.015"), new BigDecimal("0.006"), new BigDecimal(turnCost));
    }

    @Test
    @DisplayName("Série 6 × 2 min 30, 1 retouche, 1 $/tour : voix 0,29–0,44 · agent 16–32 · total 16,29–32,44 ; 42 min de rendu")
    void series() {
        VideoQuoteCalculator.Result result = VideoQuoteCalculator.compute(new VideoQuoteCalculator.Input(
                VideoQuoteKind.SERIES, 6, new BigDecimal("2.5"), 2_250, 1), rates("1.00"), DEFAULTS);

        assertThat(result.voiceLow()).isEqualByComparingTo("0.29");
        assertThat(result.voiceHigh()).isEqualByComparingTo("0.44");
        assertThat(result.turnsLow()).isEqualByComparingTo("16");
        assertThat(result.turnsHigh()).isEqualByComparingTo("32");
        assertThat(result.agentLow()).isEqualByComparingTo("16.00");
        assertThat(result.agentHigh()).isEqualByComparingTo("32.00");
        assertThat(result.low()).isEqualByComparingTo("16.29");
        assertThat(result.high()).isEqualByComparingTo("32.44");
        assertThat(result.totalMinutes()).isEqualByComparingTo("15");
        assertThat(result.renderMinutes()).isEqualTo(42);
        assertThat(VideoQuoteService.line(result))
                .isEqualTo("Coût estimé : 16,29 à 32,44 $ (voix 0,29 à 0,44 $ · agent 16,00 à 32,00 $ · rendu 0 $ — "
                        + "votre poste)");
    }

    @Test
    @DisplayName("Vidéo unique 2 min 30 à 0,87 $/tour : 2,22 à 4,43 $, pas de tours de plan ; arrondi bas / haut")
    void single() {
        VideoQuoteCalculator.Result result = VideoQuoteCalculator.compute(new VideoQuoteCalculator.Input(
                VideoQuoteKind.SINGLE, 1, new BigDecimal("2.5"), 2_250, 1), rates("0.87"), DEFAULTS);

        assertThat(result.turnsLow()).isEqualByComparingTo("2.5");
        assertThat(result.turnsHigh()).isEqualByComparingTo("5");
        assertThat(result.voiceLow()).isEqualByComparingTo("0.04");
        assertThat(result.voiceHigh()).isEqualByComparingTo("0.08");
        assertThat(result.agentLow()).isEqualByComparingTo("2.17");
        assertThat(result.agentHigh()).isEqualByComparingTo("4.35");
        assertThat(result.low()).isEqualByComparingTo("2.22");
        assertThat(result.high()).isEqualByComparingTo("4.43");
        assertThat(result.renderMinutes()).isEqualTo(7);
    }

    @Test
    @DisplayName("Retouche de 42 s : voix basse 0, haute = moitié de la narration ; 0,5 à 1 tour ; 2 min de rendu")
    void retouche() {
        VideoQuoteCalculator.Result result = VideoQuoteCalculator.compute(new VideoQuoteCalculator.Input(
                VideoQuoteKind.RETOUCHE, 1, new BigDecimal("0.7"), 630, 1), rates("1.00"), DEFAULTS);

        assertThat(result.voiceLow()).isEqualByComparingTo("0");
        assertThat(result.voiceHigh()).isEqualByComparingTo("0.01");
        assertThat(result.low()).isEqualByComparingTo("0.50");
        assertThat(result.high()).isEqualByComparingTo("1.01");
        assertThat(result.renderMinutes()).isEqualTo(2);
        assertThat(VideoQuoteService.line(result)).contains("voix 0,00 à 0,01 $").contains("agent 0,50 à 1,00 $");
    }

    @Test
    @DisplayName("Sans retouche, la voix est la même en bas et en haut : une seule valeur affichée")
    void noRetoucheGivesASingleVoiceValue() {
        VideoQuoteCalculator.Result result = VideoQuoteCalculator.compute(new VideoQuoteCalculator.Input(
                VideoQuoteKind.SINGLE, 1, new BigDecimal("2"), 1_800, 0), rates("1.00"), DEFAULTS);

        assertThat(VideoQuoteService.line(result)).contains("(voix 0,04 $ · agent 1,60 à 3,20 $");
    }

    @Test
    @DisplayName("Configuration : défauts du cadrage ; valeurs nulles ou négatives retombent sur le défaut ; haut ≥ bas")
    void propertiesDefaults() {
        assertThat(DEFAULTS.charsPerMinute()).isEqualTo(900);
        assertThat(DEFAULTS.defaultTurnCostUsd()).isEqualByComparingTo("1.00");
        assertThat(DEFAULTS.minTurnSamples()).isEqualTo(5);
        assertThat(DEFAULTS.turnCostWindow()).isEqualTo(Duration.ofDays(7));
        assertThat(DEFAULTS.retoucheConfirmThresholdUsd()).isEqualByComparingTo("3.00");
        assertThat(DEFAULTS.idleCloseHours()).isEqualTo(48);
        VideoCostProperties odd = new VideoCostProperties(-1, new BigDecimal("2"), new BigDecimal("1"),
                new BigDecimal("-1"), null, null, null, null, BigDecimal.ZERO, 0, Duration.ZERO, null, null, -3);
        assertThat(odd.charsPerMinute()).isEqualTo(900);
        assertThat(odd.turnsPerMinuteHigh()).isEqualByComparingTo("2");
        assertThat(odd.turnsPerRetoucheLow()).isEqualByComparingTo("0.5");
        assertThat(odd.defaultTurnCostUsd()).isEqualByComparingTo("1.00");
        assertThat(odd.minTurnSamples()).isEqualTo(5);
        assertThat(odd.turnCostWindow()).isEqualTo(Duration.ofDays(7));
        assertThat(odd.idleCloseHours()).isEqualTo(48);
    }

    @Test
    @DisplayName("Durées lisibles : 42 s, 2 min 30, 15 min, 1 h 05")
    void durations() {
        assertThat(VideoQuoteService.duration(new BigDecimal("0.7"))).isEqualTo("42 s");
        assertThat(VideoQuoteService.duration(new BigDecimal("2.5"))).isEqualTo("2 min 30");
        assertThat(VideoQuoteService.duration(new BigDecimal("15"))).isEqualTo("15 min");
        assertThat(VideoQuoteService.duration(new BigDecimal("65"))).isEqualTo("1 h 05");
    }
}
