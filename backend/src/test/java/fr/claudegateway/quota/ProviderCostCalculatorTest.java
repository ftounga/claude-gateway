package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Le coût <b>réel</b> d'un tour (F-133 / SF-133-01) : chaque nature à son tarif, et le tarif du
 * modèle servi.
 *
 * <p>Les montants attendus viennent de la grille officielle relevée le 2026-09-20. Aucun n'est
 * décidé ici — ce sont les prix publiés par le fournisseur.</p>
 */
class ProviderCostCalculatorTest {

    private final ProviderCostCalculator calculator = new ProviderCostCalculator(
            new ProviderPricingProperties(null, null, null, null, null, null));

    @Test
    void chargesEachNatureAtItsOwnRate() {
        // L'exemple chiffré du critère d'acceptation de la mini-spec :
        // (10 000×5 + 5 000×25 + 40 000×0,50 + 8 000×10) ÷ 1e6 = 0,275 $.
        TurnCost cost = calculator.calculate(
                new TurnTokens(10_000L, 5_000L, 40_000L, 8_000L), "claude-opus-5");

        assertThat(cost.amountUsd()).isEqualByComparingTo("0.275000");
        assertThat(cost.source()).isEqualTo(TurnCost.Source.CALCULATED);
        assertThat(cost.model()).isEqualTo("claude-opus-5");
        assertThat(cost.pricingFallback()).isFalse();
    }

    @Test
    void chargesCacheWritesAtTheOneHourRate() {
        // C'EST L'ÉCART QUI JUSTIFIE UNE SECONDE GRILLE. La boucle pose un cache TTL 1 h depuis
        // F-130 : son écriture coûte 2× l'entrée (10,00), pas 1,25× (6,25). Le décompte commercial
        // garde volontairement 6,25 en faveur du client ; la vérité, elle, dit 10,00.
        TurnCost cost = calculator.calculate(
                new TurnTokens(0L, 0L, 0L, 1_000_000L), "claude-opus-5");

        assertThat(cost.amountUsd()).isEqualByComparingTo("10.000000");
        assertThat(new BilledTokensCalculator(
                new TokenPricingProperties(null, null, null, null, null, null))
                .costUsd(new TurnTokens(0L, 0L, 0L, 1_000_000L)))
                .isEqualByComparingTo("6.25");
    }

    @Test
    void anOpusTurnCostsFiveHaikuTurns() {
        // À volume égal. C'est pour cela que le modèle est enregistré : sans lui, un tour de Haiku
        // et un tour d'Opus se ressembleraient, et le coût par client serait faux dès qu'un chemin
        // change de modèle.
        TurnTokens tokens = new TurnTokens(100_000L, 20_000L, 0L, 0L);

        BigDecimal opus = calculator.calculate(tokens, "claude-opus-5").amountUsd();
        BigDecimal haiku = calculator.calculate(tokens, "claude-haiku-4-5").amountUsd();

        assertThat(opus).isEqualByComparingTo(haiku.multiply(BigDecimal.valueOf(5)));
    }

    @Test
    void readsFableCacheAtItsOwnRatioNotTheUsualOne() {
        // 0,025× l'entrée sur cette famille, et non 0,1× : la note 1 de la grille officielle.
        // Recopier le ratio habituel multiplierait ce coût par quatre.
        TurnCost cost = calculator.calculate(
                new TurnTokens(0L, 0L, 1_000_000L, 0L), "claude-fable-5-1");

        assertThat(cost.amountUsd()).isEqualByComparingTo("0.250000");
    }

    @Test
    void fallsBackOnTheDefaultModelWhenTheModelIsUnknown() {
        TurnCost cost = calculator.calculate(
                new TurnTokens(1_000_000L, 0L, 0L, 0L), "un-modele-qui-n-existe-pas");

        // Tarif d'Opus 5, le modèle de repli configuré.
        assertThat(cost.amountUsd()).isEqualByComparingTo("5.000000");
        assertThat(cost.pricingFallback()).isTrue();
        // Le modèle inconnu est conservé tel quel : c'est lui qu'il faudra ajouter à la grille.
        assertThat(cost.model()).isEqualTo("un-modele-qui-n-existe-pas");
    }

    @Test
    void neverFailsOnAPricingProblem() {
        // Le fournisseur a déjà été appelé et payé quand ce calcul s'exécute : un tarif manquant
        // est un défaut d'information, jamais un défaut de service.
        assertThatCode(() -> calculator.calculate(new TurnTokens(1L, 1L, 1L, 1L), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> calculator.calculate(new TurnTokens(0L, 0L, 0L, 0L), ""))
                .doesNotThrowAnyException();
        assertThat(calculator.calculate(new TurnTokens(-5L, -5L, -5L, -5L), null).amountUsd())
                .isEqualByComparingTo("0.000000");
    }

    @Test
    void theProviderReportedCostPrevailsOverTheTokens() {
        // Le fournisseur sait des choses que les tokens ignorent : le modèle réellement servi, les
        // recherches web, le temps de bac à sable.
        TurnCost cost = calculator.calculate(new BigDecimal("0.80"),
                new TurnTokens(1_000_000L, 1_000_000L, 0L, 0L), "claude-opus-5");

        assertThat(cost.amountUsd()).isEqualByComparingTo("0.800000");
        assertThat(cost.source()).isEqualTo(TurnCost.Source.PROVIDER);
        assertThat(cost.pricingFallback()).isFalse();
    }

    @Test
    void fallsBackOnTheTokensWhenTheReportedCostIsEmpty() {
        // Un tour servi n'est jamais gratuit : un coût nul ou négatif rapporté est une anomalie du
        // fournisseur, pas une remise.
        assertThat(calculator.calculate(null, new TurnTokens(1_000_000L, 0L, 0L, 0L),
                "claude-opus-5").source()).isEqualTo(TurnCost.Source.CALCULATED);
        assertThat(calculator.calculate(BigDecimal.ZERO, new TurnTokens(1_000_000L, 0L, 0L, 0L),
                "claude-opus-5").amountUsd()).isEqualByComparingTo("5.000000");
        assertThat(calculator.calculate(new BigDecimal("-3"), new TurnTokens(1_000_000L, 0L, 0L, 0L),
                "claude-opus-5").source()).isEqualTo(TurnCost.Source.CALCULATED);
    }

    @Test
    void carriesThePricingVersionWithEveryAmount() {
        // Les prix changent. Sans cette date, un montant ancien deviendrait inexplicable.
        assertThat(calculator.calculate(new TurnTokens(10L, 10L, 0L, 0L), "claude-opus-5")
                .pricingVersion()).isEqualTo("2026-09-20");
    }

    @Test
    void anEmptyConfiguredGridFallsBackOnTheBuiltInOne() {
        // Une grille vide n'est pas un réglage : ce serait facturer zéro, donc ne rien mesurer.
        ProviderCostCalculator bare = new ProviderCostCalculator(
                new ProviderPricingProperties("", "", Map.of(), null, null, null));

        assertThat(bare.calculate(new TurnTokens(1_000_000L, 0L, 0L, 0L), "claude-opus-5")
                .amountUsd()).isEqualByComparingTo("5.000000");
    }

    // ------------------------------------------------ les dépenses hors tokens (SF-133-08)

    @Test
    void chargesWebSearchesPerThousand() {
        // 10 $ les mille : trois recherches valent trois centimes, et aucun compteur de tokens ne
        // les aurait révélées.
        TurnCost cost = calculator.calculate(TurnTokens.of(0L, 0L), new TurnExtras(3L, 0L),
                "claude-opus-5");

        assertThat(cost.amountUsd()).isEqualByComparingTo("0.030000");
    }

    @Test
    void chargesSessionTimePerHour() {
        // 0,08 $ l'heure : un quart d'heure de session vaut deux centimes. Ces secondes étaient
        // comptées depuis F-30 et n'avaient jamais été tarifées.
        TurnCost cost = calculator.calculate(TurnTokens.of(0L, 0L), new TurnExtras(0L, 900L),
                "claude-opus-5");

        assertThat(cost.amountUsd()).isEqualByComparingTo("0.020000");
    }

    @Test
    void addsExtrasOnTopOfTheTokens() {
        // (10 000×5 + 5 000×25) ÷ 1e6 = 0,175 $ de tokens, plus 2 × 0,01 $ de recherches.
        TurnCost cost = calculator.calculate(TurnTokens.of(10_000L, 5_000L),
                new TurnExtras(2L, 0L), "claude-opus-5");

        assertThat(cost.amountUsd()).isEqualByComparingTo("0.195000");
    }

    @Test
    void doesNotAddExtrasWhenTheProviderAlreadyReportedItsCost() {
        // LE PIÈGE DE LA SUBFEATURE : le coût rapporté par les Managed Agents comprend DÉJÀ leurs
        // recherches web et leur temps de session. Les ajouter les compterait deux fois — et
        // lourdement, puisque c'est précisément sur ce chemin que le temps de session existe.
        TurnCost cost = calculator.calculate(new BigDecimal("0.80"), TurnTokens.of(1_000L, 500L),
                new TurnExtras(50L, 3_600L), "claude-opus-5");

        assertThat(cost.amountUsd()).isEqualByComparingTo("0.800000");
        assertThat(cost.source()).isEqualTo(TurnCost.Source.PROVIDER);
    }

    @Test
    void emptyOrNegativeExtrasCostNothing() {
        assertThat(calculator.calculate(TurnTokens.of(0L, 0L), TurnExtras.NONE, "claude-opus-5")
                .amountUsd()).isEqualByComparingTo("0.000000");
        assertThat(calculator.calculate(TurnTokens.of(0L, 0L), new TurnExtras(-5L, -900L),
                "claude-opus-5").amountUsd()).isEqualByComparingTo("0.000000");
        assertThatCode(() -> calculator.calculate(TurnTokens.of(1L, 1L), null, "claude-opus-5"))
                .doesNotThrowAnyException();
    }

    @Test
    void aTurnWithoutTokensButWithSearchesStillCosts() {
        // Un tour peut n'avoir consommé aucun token et avoir tout de même coûté. Le traiter comme
        // vide ferait disparaître la dépense du relevé.
        assertThat(calculator.calculate(TurnTokens.of(0L, 0L), new TurnExtras(1L, 0L),
                "claude-opus-5").amountUsd()).isEqualByComparingTo("0.010000");
    }
}
