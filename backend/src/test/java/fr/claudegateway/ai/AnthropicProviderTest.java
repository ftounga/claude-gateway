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
        AnthropicProvider provider = new AnthropicProvider(properties, RestClient.builder(), new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> provider.complete(
                new ChatCompletionRequest("claude-opus-4-8",
                        List.of(new ChatMessage(ChatRole.USER, "Salut")))))
                .isInstanceOf(AIProviderUnavailableException.class);
    }

    @Test
    void usesByokOverrideKeyEvenWithoutPlatformKey() {
        // Aucune clé plateforme, base URL injoignable (échec réseau rapide).
        AnthropicProperties properties = new AnthropicProperties(
                "", "http://localhost:1", null, null, null, null, Duration.ofMillis(200));
        AnthropicProvider provider = new AnthropicProvider(properties, RestClient.builder(), new com.fasterxml.jackson.databind.ObjectMapper());

        // Une clé BYOK est fournie sur la requête : on dépasse le gate « non configuré » (sinon 503)
        // et l'appel est tenté (il échoue ici pour cause réseau => AIProviderException, pas Unavailable).
        assertThatThrownBy(() -> provider.complete(new ChatCompletionRequest(
                "claude-opus-4-8",
                List.of(new ChatMessage(ChatRole.USER, "ping")),
                List.of(),
                "sk-ant-byok-override-key")))
                .isInstanceOf(AIProviderException.class)
                .isNotInstanceOf(AIProviderUnavailableException.class);
    }

    @Test
    void uploadFileThrowsUnavailableWhenApiKeyMissing() {
        AnthropicProperties properties = new AnthropicProperties(
                "", null, null, null, null, null, Duration.ofSeconds(1));
        AnthropicProvider provider = new AnthropicProvider(properties, RestClient.builder(), new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> provider.uploadFile(
                new ProviderFileUpload("doc.pdf", "application/pdf", new byte[] {1, 2, 3})))
                .isInstanceOf(AIProviderUnavailableException.class);
    }
}
