package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * F-107 / SF-107-01 — le prix de l'option Forge dépend du plan porteur : 40 € sur Solo/Pro, 70 € sur
 * BYOK, chacun avec son price ID. Ces tests figent qu'une clé ne sert jamais pour l'autre plan, et
 * que la liaison Spring (deux constructeurs sur le record) lit bien les nouvelles clés.
 */
class BillingPropertiesAtelierOptionByokTest {

    private static BillingProperties.Stripe stripe(String secret, String soloPrice, String soloAmount,
            String byokPrice, String byokAmount) {
        return new BillingProperties.Stripe(secret, "wh", Map.of(), Map.of(), null, null, Map.of(),
                soloPrice, soloAmount, Map.of(), Map.of(), Map.of(), byokPrice, byokAmount);
    }

    @Test
    void byokHasItsOwnPriceAndAmount() {
        BillingProperties.Stripe stripe = stripe("sk", "price_solo", "40", "price_byok", "70");

        assertThat(stripe.atelierOptionPriceId(PlanCode.BYOK)).isEqualTo("price_byok");
        assertThat(stripe.atelierOptionDisplayPrice(PlanCode.BYOK)).isEqualTo("70");
        assertThat(stripe.atelierOptionPriceId(PlanCode.SOLO)).isEqualTo("price_solo");
        assertThat(stripe.atelierOptionPriceId(PlanCode.PRO)).isEqualTo("price_solo");
        assertThat(stripe.atelierOptionDisplayPrice(PlanCode.PRO)).isEqualTo("40");
        // Un essai (aucun plan) voit l'option au prix de Solo/Pro : comportement d'avant F-107.
        assertThat(stripe.atelierOptionDisplayPrice(null)).isEqualTo("40");
    }

    @Test
    void byokAmountDefaultsToSeventyWhenBlank() {
        assertThat(stripe("sk", "p", "40", "", " ").atelierOptionDisplayPrice(PlanCode.BYOK))
                .isEqualTo("70");
        assertThat(stripe("sk", "p", "40", null, null).atelierOptionDisplayPrice(PlanCode.BYOK))
                .isEqualTo("70");
    }

    @Test
    void byokOptionIsDormantWithoutItsOwnPriceEvenWhenSoloIsSellable() {
        BillingProperties.Stripe stripe = stripe("sk", "price_solo", "40", "", "70");

        assertThat(stripe.isAtelierOptionConfigured(PlanCode.SOLO)).isTrue();
        assertThat(stripe.isAtelierOptionConfigured(PlanCode.BYOK)).isFalse();
        assertThat(stripe("", "price_solo", "40", "price_byok", "70")
                .isAtelierOptionConfigured(PlanCode.BYOK)).as("sans clé Stripe, rien n'est vendable").isFalse();
    }

    @Test
    void legacyConstructorLeavesByokUnsellableAtTheDefaultAmount() {
        BillingProperties.Stripe stripe = new BillingProperties.Stripe("sk", "wh", Map.of(), Map.of(),
                null, null, Map.of(), "price_solo", "40", Map.of(), Map.of(), Map.of());

        assertThat(stripe.atelierOptionByokPriceId()).isNull();
        assertThat(stripe.isAtelierOptionConfigured(PlanCode.BYOK)).isFalse();
        assertThat(stripe.atelierOptionDisplayPrice(PlanCode.BYOK)).isEqualTo("70");
    }

    @Test
    void springBindsTheByokKeys() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "app.billing.stripe.secret-key", "sk",
                "app.billing.stripe.atelier-option-price-id", "price_solo",
                "app.billing.stripe.atelier-option-byok-price-id", "price_byok",
                "app.billing.stripe.atelier-option-byok-display-price", "72")));

        BillingProperties properties = binder.bind("app.billing", BillingProperties.class).get();

        assertThat(properties.stripe().atelierOptionPriceId(PlanCode.BYOK)).isEqualTo("price_byok");
        assertThat(properties.stripe().atelierOptionDisplayPrice(PlanCode.BYOK)).isEqualTo("72");
        assertThat(properties.stripe().atelierOptionDisplayPrice(PlanCode.SOLO)).isEqualTo("40");
    }
}
