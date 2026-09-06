package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Valeurs de repli des réglages de l'Atelier — en particulier le plafond d'allers-retours de la
 * boucle d'agent (F-28 / SF-28-19), calibré sur l'usage réel et borné pour rester lisible.
 */
class AtelierPropertiesTest {

    private AtelierProperties withMaxIterations(Integer value) {
        return new AtelierProperties(null, null, null, null, null, null, value);
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

    @Test
    void leavesTheOtherLimitsUntouched() {
        AtelierProperties properties = withMaxIterations(30);
        assertThat(properties.maxEntries()).isEqualTo(2000);
        assertThat(properties.maxFileBytes()).isEqualTo(2L * 1024 * 1024);
        assertThat(properties.storage()).isEqualTo("in-memory");
    }
}
