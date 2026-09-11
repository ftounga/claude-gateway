package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Décompte au coût réel (F-63 / SF-63-01) : chaque nature de token pèse son propre coût.
 *
 * <p>Les défauts en vigueur — 5 $/M en entrée, 25 $/M en sortie, 0,50 $/M en lecture de cache,
 * 6,25 $/M en écriture, et 9 $/M pour la valeur d'un token de quota — servent de référence à tous
 * les chiffres ci-dessous. Aucun n'est décidé ici : ce sont les tarifs du fournisseur et la valeur
 * du token de quota déjà en configuration avant F-63.</p>
 */
class BilledTokensCalculatorTest {

    private final BilledTokensCalculator calculator = new BilledTokensCalculator(
            new TokenPricingProperties(null, null, null, null, null, null));

    @Test
    void anOutputTokenWeighsFiveInputTokens() {
        // 9 000 entrée = 45 000 µ$ ⇒ 5 000 tokens facturés ; 9 000 sortie = 225 000 µ$ ⇒ 25 000.
        // Le rapport est de un à cinq, et c'est tout le sujet de la feature : avant elle, les deux
        // natures pesaient pareil. (Volume choisi pour tomber juste, l'arrondi n'ayant rien à dire.)
        long input = calculator.billedTokens(new TurnTokens(9_000L, 0L, 0L, 0L));
        long output = calculator.billedTokens(new TurnTokens(0L, 9_000L, 0L, 0L));

        assertThat(output).isEqualTo(5 * input);
    }

    @Test
    void aCacheReadWeighsATenthOfAnInputToken() {
        long input = calculator.billedTokens(new TurnTokens(100_000L, 0L, 0L, 0L));
        long cacheRead = calculator.billedTokens(new TurnTokens(0L, 0L, 100_000L, 0L));

        // 100 000 lectures de cache = 50 000 µ$ ⇒ 5 556 tokens facturés, contre 55 556 en entrée.
        assertThat(cacheRead).isEqualTo(5_556L);
        assertThat(input).isEqualTo(55_556L);
    }

    @Test
    void aCacheWriteWeighsMoreThanAnInputTokenButFarLessThanAnOutputToken() {
        long cacheWrite = calculator.billedTokens(new TurnTokens(0L, 0L, 0L, 100_000L));

        // 1,25× l'entrée : écrire dans le cache coûte une prime, la relire coûte un dixième.
        assertThat(cacheWrite).isEqualTo(69_444L);
    }

    @Test
    void anAgenticTurnServedByTheCacheCostsFarLessThanItsRawVolume() {
        // Le tour réellement relevé en production : 20 764 tokens d'entrée dont l'essentiel relu du
        // cache, 544 de sortie. Décompté brut, il pesait 21 308 tokens.
        TurnTokens turn = new TurnTokens(4L, 544L, 14_114L, 465L);

        assertThat(turn.processedInputTokens()).isEqualTo(14_583L);
        // (4×5 + 544×25 + 14 114×0,5 + 465×6,25) = 23 583 µ$ ⇒ 2 620 tokens facturés pour 15 127
        // tokens traités : l'usage agentique y gagne, et c'est voulu.
        assertThat(calculator.billedTokens(turn)).isEqualTo(2_620L);
    }

    @Test
    void aGenerationTurnCostsMoreThanItsRawVolume() {
        // L'autre profil de STRATEGIE-TARIFAIRE.md §2 : trois entrées pour une sortie. C'est lui que
        // le décompte brut sous-facturait, et c'est de lui que venait la marge à 30 %.
        TurnTokens turn = new TurnTokens(3_000L, 1_000L, 0L, 0L);

        assertThat(calculator.billedTokens(turn)).isGreaterThan(turn.processedInputTokens() + 1_000L);
    }

    @Test
    void theProviderCostWinsOverTheTokensWhenItIsReported() {
        // 0,90 $ au taux de 9 $/M : le fournisseur sait ce que les tokens ignorent (modèle servi,
        // recherches web, temps de bac à sable).
        assertThat(calculator.billedTokensFromCost(new BigDecimal("0.90"))).isEqualTo(100_000L);
    }

    @Test
    void aTurnThatCostNothingChargesNothing() {
        assertThat(calculator.billedTokens(new TurnTokens(0L, 0L, 0L, 0L))).isZero();
        assertThat(calculator.billedTokensFromCost(BigDecimal.ZERO)).isZero();
        assertThat(calculator.billedTokensFromCost(new BigDecimal("-1"))).isZero();
        assertThat(calculator.billedTokensFromCost(null)).isZero();
    }

    @Test
    void aServedTurnIsNeverFree() {
        // Un coût réel mais minuscule : arrondi à zéro, une suite d'appels ne consommerait rien.
        assertThat(calculator.billedTokens(new TurnTokens(1L, 0L, 0L, 0L))).isEqualTo(1L);
    }

    @Test
    void negativeCountersNeverCreditTheQuota() {
        assertThat(calculator.billedTokens(new TurnTokens(-100L, -100L, -100L, -100L))).isZero();
    }

    @Test
    void theMarkupMultipliesWhatIsChargedWithoutTouchingAnyPrice() {
        BilledTokensCalculator doubled = new BilledTokensCalculator(new TokenPricingProperties(
                null, null, null, null, null, new BigDecimal("2.0")));

        assertThat(doubled.billedTokensFromCost(new BigDecimal("0.90"))).isEqualTo(200_000L);
    }

    @Test
    void theQuotaTokenValueSetsTheScaleNotTheRatios() {
        // Porter la valeur du token de quota à celle de l'entrée revient à dire « un token de quota
        // = un token d'entrée » : le décompte accélère d'autant, mais le rapport entre natures ne
        // bouge pas d'un pouce. C'est un levier de marge, pas une règle de comptage.
        BilledTokensCalculator onInputParity = new BilledTokensCalculator(new TokenPricingProperties(
                null, null, null, null, new BigDecimal("5.00"), null));

        assertThat(onInputParity.billedTokens(new TurnTokens(1_000L, 0L, 0L, 0L))).isEqualTo(1_000L);
        assertThat(onInputParity.billedTokens(new TurnTokens(0L, 1_000L, 0L, 0L))).isEqualTo(5_000L);
    }

    @Test
    void quotaTokensConvertBackToDollarsForTheSessionCap() {
        // Chemin inverse, celui du plafond de dépense d'une session (F-36) : 1 M de tokens de quota
        // vaut 9 $, et l'arrondi va vers le bas — un plafond trop haut dépasserait le quota.
        assertThat(calculator.usdOfQuotaTokens(1_000_000L)).isEqualByComparingTo("9.00");
        assertThat(calculator.usdOfQuotaTokens(0L)).isEqualByComparingTo("0");
        assertThat(calculator.usdOfQuotaTokens(-5L)).isEqualByComparingTo("0");
    }
}
