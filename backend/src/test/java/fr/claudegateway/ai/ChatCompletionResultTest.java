package fr.claudegateway.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import fr.claudegateway.quota.TurnTokens;

/**
 * Ventilation du cache dans le résultat d'une complétion (F-63 / SF-63-02) : c'est elle qui permet
 * au quota de facturer chaque nature de token à son prix.
 */
class ChatCompletionResultTest {

    @Test
    void withoutAnyCacheTheAccountingIsExactlyWhatItWasBefore() {
        // La passerelle ne pose aucun `cache_control` : ces champs valent 0, et le décompte de
        // `/chat` et `/ask` est strictement celui d'avant F-63.
        ChatCompletionResult completion = new ChatCompletionResult("ok", "claude-opus-5", 120, 40);

        assertThat(completion.cacheReadTokens()).isZero();
        assertThat(completion.cacheWriteTokens()).isZero();
        assertThat(completion.fullPriceInputTokens()).isEqualTo(120);
        assertThat(completion.turnTokens()).isEqualTo(new TurnTokens(120L, 40L, 0L, 0L));
    }

    @Test
    void cacheTokensAreCountedInTheVolumeAndBilledApart() {
        ChatCompletionResult completion =
                new ChatCompletionResult("ok", "claude-opus-5", 10_000, 40, 9_000, 500);

        // Le volume traité reste le volume traité…
        assertThat(completion.turnTokens().processedInputTokens()).isEqualTo(10_000L);
        // …et seule l'entrée hors cache est facturée au plein tarif.
        assertThat(completion.fullPriceInputTokens()).isEqualTo(500);
        assertThat(completion.turnTokens()).isEqualTo(new TurnTokens(500L, 40L, 9_000L, 500L));
    }

    @Test
    void aCacheLargerThanTheReportedInputNeverCreditsTheQuota() {
        // Relevé incohérent du fournisseur : on ne rend pas de tokens, on plafonne à zéro.
        ChatCompletionResult completion =
                new ChatCompletionResult("ok", "claude-opus-5", 100, 10, 500, 0);

        assertThat(completion.fullPriceInputTokens()).isZero();
    }
}
