package fr.claudegateway.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Vérifie le comportement « dormant » du fournisseur Anthropic lorsqu'aucune clé plateforme n'est
 * configurée : tout appel lève {@link AIProviderUnavailableException} (mappé en 503), sans réseau.
 */
class AnthropicProviderTest {

    @Test
    void throwsUnavailableWhenApiKeyMissing() {
        AnthropicProperties properties = new AnthropicProperties(
                "", null, null, null, null, null, Duration.ofSeconds(1));
        AnthropicProvider provider = new AnthropicProvider(properties, RestClient.builder());

        assertThatThrownBy(() -> provider.complete(
                new ChatCompletionRequest("claude-opus-4-8",
                        List.of(new ChatMessage(ChatRole.USER, "Salut")))))
                .isInstanceOf(AIProviderUnavailableException.class);
    }
}
