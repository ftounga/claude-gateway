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
        return new AnthropicProperties("k", null, null, null, null, null, value, Duration.ofSeconds(1), null, null);
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

    // --- Tenue longue de la boucle d'agent (F-39 / SF-39-11) ------------------------------------

    private AnthropicProperties withAgentTenacity(Duration agentTimeout, Integer agentMaxAttempts) {
        return new AnthropicProperties("k", null, null, null, null, null, null,
                Duration.ofSeconds(120), agentTimeout, agentMaxAttempts);
    }

    @Test
    void agentTimeoutFallsBackToFiveMinutes() {
        assertThat(withAgentTenacity(null, null).agentTimeout())
                .isEqualTo(AnthropicProperties.DEFAULT_AGENT_TIMEOUT);
        assertThat(withAgentTenacity(Duration.ZERO, null).agentTimeout())
                .isEqualTo(AnthropicProperties.DEFAULT_AGENT_TIMEOUT);
        assertThat(withAgentTenacity(Duration.ofSeconds(-1), null).agentTimeout())
                .isEqualTo(AnthropicProperties.DEFAULT_AGENT_TIMEOUT);
    }

    @Test
    void agentTimeoutIsIndependentFromTheChatTimeout() {
        // Le chat est streamé et user-facing ; la boucle appelle en non-streamé. Les relier ferait
        // porter à l'un un besoin qui n'est pas le sien.
        AnthropicProperties properties = withAgentTenacity(Duration.ofMinutes(9), null);
        assertThat(properties.agentTimeout()).isEqualTo(Duration.ofMinutes(9));
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void agentAttemptsFallBackAndStayWithinBounds() {
        // Une valeur aberrante est ramenée dans les bornes ; elle n'empêche pas le démarrage.
        assertThat(withAgentTenacity(null, null).agentMaxAttempts()).isEqualTo(3);
        assertThat(withAgentTenacity(null, 0).agentMaxAttempts()).isEqualTo(3);
        assertThat(withAgentTenacity(null, -4).agentMaxAttempts()).isEqualTo(3);
        assertThat(withAgentTenacity(null, 99).agentMaxAttempts()).isEqualTo(5);
        assertThat(withAgentTenacity(null, 2).agentMaxAttempts()).isEqualTo(2);
    }
}
