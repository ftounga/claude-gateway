package fr.claudegateway.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Valeurs de repli des propriétés du fournisseur — en particulier le plafond de sortie de la boucle
 * d'agent (F-28 / SF-28-18), distinct de celui du chat.
 */
class AnthropicPropertiesTest {

    private AnthropicProperties withAgentMaxTokens(Integer value) {
        return new AnthropicProperties("k", null, null, null, null, null, value, Duration.ofSeconds(1));
    }

    @Test
    void agentCapFallsBackToSixteenThousandWhenAbsent() {
        assertThat(withAgentMaxTokens(null).agentMaxTokens()).isEqualTo(16_384);
    }

    @Test
    void agentCapFallsBackWhenNonPositive() {
        assertThat(withAgentMaxTokens(0).agentMaxTokens()).isEqualTo(16_384);
        assertThat(withAgentMaxTokens(-1).agentMaxTokens()).isEqualTo(16_384);
    }

    @Test
    void agentCapIsHonouredWhenConfigured() {
        assertThat(withAgentMaxTokens(8_000).agentMaxTokens()).isEqualTo(8_000);
    }

    @Test
    void chatCapStaysAtItsOwnDefaultAndIsIndependent() {
        // La boucle d'agent ne doit pas entraîner le chat (F-02) dans son changement de plafond.
        AnthropicProperties properties = withAgentMaxTokens(32_000);
        assertThat(properties.maxTokens()).isEqualTo(4096);
    }
}
