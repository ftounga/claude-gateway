package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Valeurs de repli des réglages de l'Atelier — en particulier le plafond d'allers-retours de la
 * boucle d'agent (F-28 / SF-28-19), calibré sur l'usage réel et borné pour rester lisible.
 */
class AtelierPropertiesTest {

    private AtelierProperties withMaxIterations(Integer value) {
        return new AtelierProperties(null, null, null, null, null, null, value, null, null, null, null);
    }

    @Test
    void defaultsToThirtySteps() {
        assertThat(withMaxIterations(null).maxIterations()).isEqualTo(30);
    }

    @Test
    void fallsBackToTheDefaultWhenNonPositive() {
        assertThat(withMaxIterations(0).maxIterations()).isEqualTo(30);
        assertThat(withMaxIterations(-5).maxIterations()).isEqualTo(30);
    }

    @Test
    void honoursAConfiguredValue() {
        assertThat(withMaxIterations(12).maxIterations()).isEqualTo(12);
        assertThat(withMaxIterations(50).maxIterations()).isEqualTo(50);
    }

    @Test
    void capsUnreasonableValuesAtOneHundred() {
        // Au-delà, le budget de temps du tour aurait tranché de toute façon.
        assertThat(withMaxIterations(150).maxIterations()).isEqualTo(100);
    }

    // ------------------------------------------------- SF-39-10 : modèle et effort de la boucle

    private AtelierProperties withReasoning(String model, String effort) {
        return new AtelierProperties(null, null, null, null, null, null, null, model, effort, null, null);
    }

    @Test
    void defaultsToTheHarnessModelAndTheProviderEffort() {
        AtelierProperties properties = withReasoning(null, null);
        assertThat(properties.model()).isEqualTo("claude-opus-5");
        // `high` est déjà le défaut du fournisseur : l'écrire ne change rien, mais le rend réglable.
        assertThat(properties.effort()).isEqualTo("high");
    }

    @Test
    void honoursAConfiguredModelAndEffort() {
        AtelierProperties properties = withReasoning("claude-opus-4-8", "xhigh");
        assertThat(properties.model()).isEqualTo("claude-opus-4-8");
        assertThat(properties.effort()).isEqualTo("xhigh");
    }

    @Test
    void fallsBackToTheDefaultEffortWhenTheConfiguredOneIsUnknown() {
        // Une faute de frappe en configuration ne doit pas empêcher un tour de partir.
        assertThat(withReasoning(null, "turbo").effort()).isEqualTo("high");
        assertThat(withReasoning(null, "  ").effort()).isEqualTo("high");
    }

    @Test
    void fallsBackToTheDefaultModelWhenTheConfiguredOneIsBlank() {
        assertThat(withReasoning("   ", null).model()).isEqualTo("claude-opus-5");
    }

    // ------------------------------------------- SF-39-12 : écartement des résultats périmés

    @Test
    void prunesStaleToolResultsByDefault() {
        assertThat(withMaxIterations(null).contextPruning()).isTrue();
    }

    @Test
    void honoursTheCircuitBreaker() {
        // Capacité beta dans un chemin critique : le coupe-circuit rétablit le service sans
        // livraison si le fournisseur retirait l'option (D-L6-11).
        AtelierProperties off =
                new AtelierProperties(null, null, null, null, null, null, null, null, null, false, null);
        assertThat(off.contextPruning()).isFalse();
    }

    /** Plafond de consommation d'un message (F-39 / SF-39-15). */
    private static AtelierProperties withTurnCap(Long value) {
        return new AtelierProperties(null, null, null, null, null, null, null, null, null, null, value);
    }

    @Test
    void defaultsToTheMeasuredTurnCeiling() {
        // Calibré sur l'usage réel du cadrage : contexte maximal observé 900 519 tokens, tour de
        // 30 itérations estimé à ~1,35 M tokens d'entrée. Un tour ordinaire ne le voit jamais.
        assertThat(withTurnCap(null).maxTurnTokens())
                .isEqualTo(AtelierProperties.DEFAULT_MAX_TURN_TOKENS);
        assertThat(withTurnCap(0L).maxTurnTokens()).isEqualTo(AtelierProperties.DEFAULT_MAX_TURN_TOKENS);
        assertThat(withTurnCap(-1L).maxTurnTokens()).isEqualTo(AtelierProperties.DEFAULT_MAX_TURN_TOKENS);
    }

    @Test
    void capsTheTurnCeilingSoItStaysMeaningful() {
        // Au-delà, le plafond d'étapes et le budget de temps auraient tranché de toute façon :
        // mieux vaut une borne lisible qu'un plafond qui n'a jamais l'occasion de s'appliquer.
        assertThat(withTurnCap(50_000_000L).maxTurnTokens())
                .isEqualTo(AtelierProperties.MAX_TURN_TOKENS_CEILING);
        assertThat(withTurnCap(250_000L).maxTurnTokens()).isEqualTo(250_000L);
    }

    @Test
    void leavesTheOtherLimitsUntouched() {
        AtelierProperties properties = withMaxIterations(30);
        assertThat(properties.maxEntries()).isEqualTo(2000);
        assertThat(properties.maxFileBytes()).isEqualTo(2L * 1024 * 1024);
        assertThat(properties.storage()).isEqualTo("in-memory");
    }
}
