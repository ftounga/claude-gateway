package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Plafond de dépense d'une session Managed Agents (F-36 / SF-36-01). */
class SessionBudgetTest {

    @Test
    void anAmountInDollarsBecomesMinorUnitsAsAString() {
        // Le fournisseur rejette les formes décimales : "200", jamais "2.00".
        assertThat(SessionBudget.ofUsd(new BigDecimal("2.00")).amountAsString()).isEqualTo("200");
        assertThat(SessionBudget.ofUsd(new BigDecimal("0.10")).amountAsString()).isEqualTo("10");
        assertThat(SessionBudget.ofUsd(new BigDecimal("5")).amountAsString()).isEqualTo("500");
    }

    @Test
    void aFractionOfACentIsTruncatedDownwardNeverUpward() {
        // Arrondir au supérieur autoriserait à dépenser au-delà de ce qui a été calculé.
        assertThat(SessionBudget.ofUsd(new BigDecimal("0.909")).amountAsString()).isEqualTo("90");
        assertThat(SessionBudget.ofUsd(new BigDecimal("1.999")).amountAsString()).isEqualTo("199");
    }

    @Test
    void theCurrencyDefaultsToTheProviderPricingCurrency() {
        assertThat(new SessionBudget(200L, null).currency()).isEqualTo("USD");
        assertThat(new SessionBudget(200L, "  ").currency()).isEqualTo("USD");
    }

    @Test
    void aNonPositiveCapIsRefusedBecauseItWouldPauseTheSessionBeforeTheFirstWord() {
        assertThatThrownBy(() -> new SessionBudget(0L, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionBudget(-1L, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
