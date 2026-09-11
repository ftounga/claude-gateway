package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.dto.TopUpPackResponse;

/**
 * F-67 / SF-67-01 — le montant d'affichage des packs de recharge, résolu par la configuration.
 *
 * <p>Ce que ces tests protègent tient en une phrase : <b>une configuration muette ne doit jamais
 * produire un prix</b>. Avant F-67 l'écran affichait « 29 € » pour le pack 1 M, écrit en dur dans le
 * composant Angular, alors qu'aucune source du dépôt ne portait ce montant.</p>
 */
class BillingPropertiesTopUpDisplayPriceTest {

    private static final TopUpPack DAY = new TopUpPack("DAY", "Recharge 200 k tokens", 200_000L);
    private static final TopUpPack STANDARD =
            new TopUpPack("STANDARD", "Recharge — 1 M tokens", 1_000_000L);

    private static BillingProperties.Stripe stripe(Map<String, String> topupDisplayPrices) {
        return new BillingProperties.Stripe(
                "sk_test", "whsec_test",
                Map.of("SOLO", "price_solo"),
                Map.of("DAY", "price_topup_day", "STANDARD", "price_topup_standard"),
                null, null,
                Map.of("SOLO", "24"),
                null, null,
                Map.of(), Map.of(), topupDisplayPrices);
    }

    @Test
    void resolvesConfiguredTopUpDisplayPrice() {
        assertThat(stripe(Map.of("DAY", "4,99")).topupDisplayPrice("DAY")).isEqualTo("4,99");
    }

    @Test
    void returnsNullWhenNoAmountIsConfiguredForThePack() {
        // Le cas LIVRÉ du pack 1 M : vendable (il a un price ID), sans montant décidé. null est la
        // bonne réponse — c'est à l'écran de dire que le prix sera indiqué au paiement.
        BillingProperties.Stripe config = stripe(Map.of("DAY", "4,99"));

        assertThat(config.topupPriceId("STANDARD")).isEqualTo("price_topup_standard");
        assertThat(config.topupDisplayPrice("STANDARD")).isNull();
    }

    @Test
    void treatsEmptyOrBlankAmountAsAbsent() {
        // Une chaîne vide traverserait l'API et s'afficherait « €» à côté d'un bouton d'achat.
        // C'est précisément la forme que prend la variable d'environnement non renseignée.
        assertThat(stripe(Map.of("STANDARD", "")).topupDisplayPrice("STANDARD")).isNull();
        assertThat(stripe(Map.of("STANDARD", "   ")).topupDisplayPrice("STANDARD")).isNull();
    }

    @Test
    void toleratesMissingConfigurationBlockAndNullPackCode() {
        BillingProperties.Stripe config = stripe(null);

        assertThat(config.topupDisplayPrices()).isEmpty();
        assertThat(config.topupDisplayPrice("DAY")).isNull();
        assertThat(config.topupDisplayPrice(null)).isNull();
    }

    @Test
    void ignoresAmountsConfiguredForUnknownPackCodes() {
        // La configuration renseigne un montant, elle ne crée pas un pack : le catalogue seul décide
        // de ce qui se vend.
        Map<String, String> prices = new HashMap<>();
        prices.put("GHOST", "99");
        BillingProperties.Stripe config = stripe(prices);

        assertThat(config.topupDisplayPrice("DAY")).isNull();
        assertThat(config.topupDisplayPrice("GHOST")).isEqualTo("99");
    }

    @Test
    void projectsPackWithItsAmountAndNeverInventsOne() {
        BillingProperties.Stripe config = stripe(Map.of("DAY", "4,99"));

        TopUpPackResponse priced = TopUpPackResponse.of(DAY, config.topupDisplayPrice(DAY.code()));
        TopUpPackResponse unpriced =
                TopUpPackResponse.of(STANDARD, config.topupDisplayPrice(STANDARD.code()));

        assertThat(priced.code()).isEqualTo("DAY");
        assertThat(priced.label()).isEqualTo("Recharge 200 k tokens");
        assertThat(priced.tokens()).isEqualTo(200_000L);
        assertThat(priced.priceEur()).isEqualTo("4,99");

        assertThat(unpriced.tokens()).isEqualTo(1_000_000L);
        assertThat(unpriced.priceEur()).isNull();
    }

    @Test
    void planDisplayPricesAreUntouchedByTopUpDisplayPrices() {
        // Non-régression : le montant des recharges n'emprunte rien à celui des plans.
        BillingProperties.Stripe config = stripe(Map.of("DAY", "4,99", "SOLO", "1"));

        assertThat(config.displayPrice(PlanCode.SOLO)).isEqualTo("24");
        assertThat(config.priceId(PlanCode.SOLO)).isEqualTo("price_solo");
    }
}
