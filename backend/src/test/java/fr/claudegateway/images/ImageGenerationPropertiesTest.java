package fr.claudegateway.images;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les défauts et le drapeau « configuré » du fournisseur d'images (F-142 / SF-142-04). */
class ImageGenerationPropertiesTest {

    @Test
    @DisplayName("défauts : base-url OpenAI, modèle gpt-image-1, timeout 60s, bornes prudentes")
    void defaults() {
        ImageGenerationProperties p = new ImageGenerationProperties(null, null, null, null, null, null,
                null, null, null);
        assertThat(p.baseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(p.model()).isEqualTo("gpt-image-1");
        assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.maxImageBytes()).isEqualTo(ImageGenerationProperties.DEFAULT_MAX_IMAGE_BYTES);
        assertThat(p.maxPromptChars()).isEqualTo(1000);
        assertThat(p.maxPerTurn()).isEqualTo(3);
        assertThat(p.maxAccountImages()).isEqualTo(500);
        assertThat(p.costEurPerImage()).isEqualByComparingTo(new BigDecimal("0.04"));
    }

    @Test
    @DisplayName("non configuré sans clé (drapeau fort) ; configuré avec base-url ET clé")
    void isConfigured() {
        assertThat(new ImageGenerationProperties("https://api.openai.com/v1", null, null, null, null, null,
                null, null, null).isConfigured()).isFalse();
        assertThat(new ImageGenerationProperties("https://api.openai.com/v1", "  ", null, null, null, null,
                null, null, null).isConfigured()).isFalse();
        assertThat(new ImageGenerationProperties("https://api.openai.com/v1", "sk-secret", null, null, null,
                null, null, null, null).isConfigured()).isTrue();
    }

    @Test
    @DisplayName("valeurs hors bornes ramenées au défaut (timeout, tailles, coût négatif)")
    void clampsInvalid() {
        ImageGenerationProperties p = new ImageGenerationProperties("x", "k", "m", Duration.ZERO, 0L, -5, 0,
                -1, new BigDecimal("-2"));
        assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.maxImageBytes()).isEqualTo(ImageGenerationProperties.DEFAULT_MAX_IMAGE_BYTES);
        assertThat(p.maxPromptChars()).isEqualTo(1000);
        assertThat(p.maxPerTurn()).isEqualTo(3);
        assertThat(p.maxAccountImages()).isEqualTo(500);
        assertThat(p.costEurPerImage()).isEqualByComparingTo(new BigDecimal("0.04"));
    }
}
