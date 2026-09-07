package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * F-43 / SF-43-01 — la périodicité annuelle existe, et rien d'autre n'a bougé.
 *
 * <p>Ces tests valent surtout comme garde-fou de contrat : {@code BillingPeriod} est persisté en
 * base (SF-43-02) et sérialisé dans l'API du catalogue. Renommer ou retirer une valeur casserait
 * silencieusement des lignes existantes et un contrat client.</p>
 */
class BillingPeriodTest {

    @Test
    void carriesYearlyAlongsideMonthlyAndDaily() {
        assertThat(BillingPeriod.values())
                .containsExactly(BillingPeriod.MONTHLY, BillingPeriod.DAILY, BillingPeriod.YEARLY);
    }

    @Test
    void resolvesYearlyByName() {
        assertThat(BillingPeriod.valueOf("YEARLY")).isEqualTo(BillingPeriod.YEARLY);
    }
}
