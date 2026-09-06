package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Plafond de consommation d'un message (F-39 / SF-39-15). Ce qui est figé ici : le plafond ne
 * dépasse jamais le quota restant, la première itération n'est jamais refusée, et la projection
 * majore l'itération à venir au lieu de la constater après coup.
 */
class AtelierTurnBudgetTest {

    @Test
    void hostedIsBoundedByTheRemainingQuota() {
        // Un message ne consomme jamais plus que ce qui a été payé : c'est la règle de F-36
        // (sessionBudget), transposée à la boucle maison.
        assertThat(AtelierTurnBudget.hosted(1_500_000L, 40_000L).maxTokens()).isEqualTo(40_000L);
        // Quota confortable : c'est le réglage qui borne.
        assertThat(AtelierTurnBudget.hosted(1_500_000L, 9_000_000L).maxTokens()).isEqualTo(1_500_000L);
    }

    @Test
    void hostedNeverGoesBelowOneToken() {
        // Quota épuisé (assertWithinQuota a déjà tranché en amont) : le plafond reste positif, si
        // bien que la première itération part quand même — un tour vide se lirait comme une panne.
        assertThat(AtelierTurnBudget.hosted(1_500_000L, 0L).maxTokens()).isEqualTo(1L);
        assertThat(AtelierTurnBudget.hosted(1_500_000L, -5L).maxTokens()).isEqualTo(1L);
    }

    @Test
    void byokIgnoresTheQuotaAndKeepsTheConfiguredCeiling() {
        // En BYOK les tokens sont sur le compte du client, où la plateforme ne tient aucun quota
        // (SF-28-06) — le plafond reste utile : il borne un tour parti en vrille, quel qu'en soit
        // le payeur.
        assertThat(AtelierTurnBudget.byok(1_500_000L).maxTokens()).isEqualTo(1_500_000L);
    }

    @Test
    void theFirstIterationIsNeverRefused() {
        // Aucune itération observée => aucune projection => on part. Décision D-L8-3 : refuser
        // avant tout appel produirait un tour qui n'a rien fait, rien dit et rien coûté.
        AtelierTurnBudget tiny = new AtelierTurnBudget(1L);
        assertThat(tiny.exceededBy(0L, 0L)).isFalse();
    }

    @Test
    void projectsTheNextIterationInsteadOfConstatingAfterwards() {
        AtelierTurnBudget budget = new AtelierTurnBudget(100L);

        // 60 dépensés, une itération de 30 déjà vue : 90 <= 100, on continue.
        assertThat(budget.exceededBy(60L, 30L)).isFalse();
        // Pile au plafond : on continue (le plafond est atteignable, pas interdit).
        assertThat(budget.exceededBy(70L, 30L)).isFalse();
        // 80 + 30 = 110 > 100 : on renonce AVANT l'appel, sans quoi une itération entière
        // passerait au-delà du plafond (décision D-L8-2).
        assertThat(budget.exceededBy(80L, 30L)).isTrue();
    }
}
