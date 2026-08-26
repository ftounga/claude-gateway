package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bornes du diff des modifications d'un tour (F-37 / SF-37-01). */
class AtelierDiffPropertiesTest {

    @Test
    @DisplayName("valeurs absentes : bornes du cadrage (400 lignes par fichier, 50 fichiers)")
    void missingValuesFallBackToTheDefaults() {
        AtelierDiffProperties properties = new AtelierDiffProperties(null, null);

        assertThat(properties.maxLines()).isEqualTo(400);
        assertThat(properties.maxFiles()).isEqualTo(50);
    }

    @Test
    @DisplayName("valeurs nulles ou négatives : bornes par défaut, jamais un diff désactivé en silence")
    void nonPositiveValuesFallBackToTheDefaults() {
        AtelierDiffProperties properties = new AtelierDiffProperties(0, -5);

        assertThat(properties.maxLines()).isEqualTo(400);
        assertThat(properties.maxFiles()).isEqualTo(50);
    }

    @Test
    @DisplayName("valeurs configurées : reprises telles quelles")
    void configuredValuesAreKept() {
        AtelierDiffProperties properties = new AtelierDiffProperties(120, 5);

        assertThat(properties.maxLines()).isEqualTo(120);
        assertThat(properties.maxFiles()).isEqualTo(5);
    }
}
