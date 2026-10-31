package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** F-183 / SF-183-03 — le plafond en dollars : seuils, première itération, chiffre inconnu. */
class AtelierSpendCapTest {

    private final AtelierSpendCap cap = new AtelierSpendCap(new BigDecimal("0.50"));

    @Test
    void refusesTheIterationThatWouldCrossTheCap() {
        assertThat(cap.exceededBy(new BigDecimal("0.30"), new BigDecimal("0.30"))).isTrue();
        assertThat(cap.exceededBy(new BigDecimal("0.20"), new BigDecimal("0.30"))).isFalse();
    }

    @Test
    void theFirstIterationAndAnUnknownFigureNeverCut() {
        assertThat(cap.exceededBy(BigDecimal.ZERO, BigDecimal.ZERO)).isFalse();
        assertThat(cap.exceededBy(new BigDecimal("9"), null)).isFalse();
        assertThat(cap.exceededBy(null, new BigDecimal("9"))).isFalse();
    }

    @Test
    void saysIncompleteAndTheBudget() {
        assertThat(cap.reply()).startsWith("INCOMPLET").contains("0,46 €");
        assertThat(new AtelierSpendCap(null).capUsd()).isPositive();
    }
}
