package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.provider.CheckoutCommand;

/**
 * F-43 / SF-43-02 — la commande de paiement porte la périodicité <b>achetée</b>.
 *
 * <p>La distinction compte : c'est elle, et non la période native du plan, qui décide du mode de
 * paiement chez le fournisseur. Un plan mensuel acheté à l'année reste un abonnement ; un pass
 * journée reste un paiement unique.</p>
 */
class CheckoutCommandTest {

    private static final Plan SOLO =
            new Plan(PlanCode.SOLO, "Solo", ProviderMode.HOSTED, BillingPeriod.MONTHLY);
    private static final Plan DAILY_PASS =
            new Plan(PlanCode.DAILY, "Pass journée", ProviderMode.HOSTED, BillingPeriod.DAILY);

    @Test
    void carriesTheRequestedPeriod() {
        CheckoutCommand command = new CheckoutCommand(
                UUID.randomUUID(), "a@b.co", null, SOLO, "price_solo_yearly", BillingPeriod.YEARLY);

        assertThat(command.period()).isEqualTo(BillingPeriod.YEARLY);
    }

    @Test
    void fallsBackToThePlansOwnPeriodWhenNoneIsGiven() {
        // Compatibilité du contrat d'origine (F-09) : un appelant qui n'achète pas d'engagement
        // particulier obtient la périodicité du plan, exactement comme avant F-43.
        assertThat(new CheckoutCommand(UUID.randomUUID(), "a@b.co", null, SOLO, "price_solo").period())
                .isEqualTo(BillingPeriod.MONTHLY);
        assertThat(new CheckoutCommand(UUID.randomUUID(), "a@b.co", null, DAILY_PASS, "price_daily")
                .period()).isEqualTo(BillingPeriod.DAILY);
    }
}
