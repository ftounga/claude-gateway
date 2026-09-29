package fr.claudegateway.atelier.recall;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Les défauts et le coupe-circuit de la config sémantique (F-162 / SF-162-06). Un seul constructeur
 * compact — le piège des deux constructeurs est évité (le contexte Spring démarre sainement).
 */
class RecallSemanticPropertiesTest {

    @Test
    @DisplayName("défauts : modèle, dimension, top-N, timeout et backfill posés ; enabled ON par défaut")
    void defaultsAreApplied() {
        RecallSemanticProperties props = new RecallSemanticProperties(
                null, null, null, null, null, null, null, null);

        assertThat(props.enabled()).isTrue();
        assertThat(props.baseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(props.model()).isEqualTo("text-embedding-3-small");
        assertThat(props.dimension()).isEqualTo(1536);
        assertThat(props.topN()).isEqualTo(5);
        assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.backfill()).isNotNull();
        assertThat(props.backfill().enabled()).isTrue();
        assertThat(props.backfill().batchSize()).isEqualTo(50);
        assertThat(props.backfill().maxPerRun()).isEqualTo(500);
        assertThat(props.backfill().interval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("isConfigured : faux sans clé (auto-désactivation), faux si coupe-circuit ouvert, vrai avec clé")
    void isConfiguredNeedsEnabledAndKey() {
        assertThat(new RecallSemanticProperties(true, null, null, null, null, null, null, null)
                .isConfigured()).isFalse();
        assertThat(new RecallSemanticProperties(true, "  ", null, null, null, null, null, null)
                .isConfigured()).isFalse();
        assertThat(new RecallSemanticProperties(false, "sk-key", null, null, null, null, null, null)
                .isConfigured()).isFalse();
        assertThat(new RecallSemanticProperties(true, "sk-key", null, null, null, null, null, null)
                .isConfigured()).isTrue();
    }
}
