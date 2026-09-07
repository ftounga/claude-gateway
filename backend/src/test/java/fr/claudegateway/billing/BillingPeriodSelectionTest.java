package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * F-43 / SF-43-02 — la décision d'achat : quelle périodicité, et quel price la facture.
 *
 * <p>Un seul composant tranche, parce que deux chemins d'achat en dépendent (souscription et
 * changement de plan). Ces tests figent surtout ce que le service <b>refuse</b> : sans eux, la
 * tentation du repli silencieux vers le mensuel reviendrait par la petite porte.</p>
 */
class BillingPeriodSelectionTest {

    private static final Plan SOLO =
            new Plan(PlanCode.SOLO, "Solo", ProviderMode.HOSTED, BillingPeriod.MONTHLY);
    private static final Plan PRO =
            new Plan(PlanCode.PRO, "Pro", ProviderMode.HOSTED, BillingPeriod.MONTHLY);
    private static final Plan DAILY_PASS =
            new Plan(PlanCode.DAILY, "Pass journée", ProviderMode.HOSTED, BillingPeriod.DAILY);

    private final BillingPeriodSelection selection = new BillingPeriodSelection(
            new BillingProperties(5, new BillingProperties.Stripe(
                    "sk", "wh",
                    Map.of("SOLO", "price_solo", "PRO", "price_pro", "DAILY", "price_daily"),
                    Map.of(), null, null, Map.of(), null, null,
                    Map.of("SOLO", "price_solo_yearly"), Map.of("SOLO", "240"))));

    @Test
    void anAbsentPeriodMeansMonthly() {
        // Le contrat d'origine n'envoyait pas de périodicité : il doit continuer de marcher.
        assertThat(selection.resolve(null)).isEqualTo(BillingPeriod.MONTHLY);
        assertThat(selection.resolve("")).isEqualTo(BillingPeriod.MONTHLY);
        assertThat(selection.resolve("   ")).isEqualTo(BillingPeriod.MONTHLY);
    }

    @Test
    void normalizesCaseAndWhitespace() {
        assertThat(selection.resolve(" yearly ")).isEqualTo(BillingPeriod.YEARLY);
        assertThat(selection.resolve("Monthly")).isEqualTo(BillingPeriod.MONTHLY);
    }

    @Test
    void refusesAnUnknownPeriodRatherThanFallingBackToMonthly() {
        assertThatThrownBy(() -> selection.resolve("WEEKLY"))
                .isInstanceOf(UnknownBillingPeriodException.class);
        assertThatThrownBy(() -> selection.resolve("annuel"))
                .isInstanceOf(UnknownBillingPeriodException.class);
    }

    @Test
    void refusesDailyAsAPurchasedPeriod() {
        assertThatThrownBy(() -> selection.resolve("DAILY"))
                .isInstanceOf(UnknownBillingPeriodException.class);
    }

    @Test
    void resolvesTheMonthlyPriceByDefault() {
        assertThat(selection.priceId(SOLO, BillingPeriod.MONTHLY)).isEqualTo("price_solo");
    }

    @Test
    void resolvesTheYearlyPriceWhenTheYearlyOfferExists() {
        assertThat(selection.priceId(SOLO, BillingPeriod.YEARLY)).isEqualTo("price_solo_yearly");
    }

    @Test
    void refusesTheYearlyPriceWhenThePlanHasNoYearlyOffer() {
        // Le repli vers price_pro ferait payer au mois qui a demandé l'année.
        assertThatThrownBy(() -> selection.priceId(PRO, BillingPeriod.YEARLY))
                .isInstanceOf(YearlyBillingUnavailableException.class);
    }

    @Test
    void aDailyPassKeepsItsOwnPeriodWithoutTheClientAskingForIt() {
        assertThat(selection.resolveFor(DAILY_PASS, null)).isEqualTo(BillingPeriod.DAILY);
        assertThat(selection.resolveFor(DAILY_PASS, "MONTHLY")).isEqualTo(BillingPeriod.DAILY);
    }

    @Test
    void aDailyPassCannotBeCommittedForAYear() {
        assertThatThrownBy(() -> selection.resolveFor(DAILY_PASS, "YEARLY"))
                .isInstanceOf(YearlyBillingUnavailableException.class);
    }

    @Test
    void aMonthlyPlanKeepsTheRequestedPeriod() {
        assertThat(selection.resolveFor(SOLO, "YEARLY")).isEqualTo(BillingPeriod.YEARLY);
        assertThat(selection.resolveFor(SOLO, null)).isEqualTo(BillingPeriod.MONTHLY);
    }
}
