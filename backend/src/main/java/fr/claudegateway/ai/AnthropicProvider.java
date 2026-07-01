package fr.claudegateway.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Implémentation {@link AIProvider} pour Anthropic Claude en mode <b>Hosted</b> : la Gateway appelle
 * l'API Anthropic avec sa clé plateforme (jamais exposée au client ni journalisée).
 *
 * <p>Seul point du code couplé à Anthropic : le domaine ne dépend que de l'interface {@link AIProvider}.
 * La clé est lue depuis {@link AnthropicProperties} (environnement uniquement). Si elle est absente,
 * chaque appel lève {@link AIProviderUnavailableException} (traduit en 503).</p>
 */
@Component
public class AnthropicProvider implements AIProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

    private final AnthropicProperties properties;
    private final RestClient restClient;

    public AnthropicProvider(AnthropicProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(AnthropicProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, properties.timeout().toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }

    @Override
    public ChatCompletionResult complete(ChatCompletionRequest request) {
        if (!properties.isConfigured()) {
            // Aucune clé => fournisseur dormant. On ne journalise jamais la clé (ici, elle est absente).
            throw new AIProviderUnavailableException("Le fournisseur IA n'est pas configuré.");
        }

        Map<String, Object> body = Map.of(
                "model", request.model(),
                "max_tokens", properties.maxTokens(),
                "messages", toApiMessages(request.messages()));

        try {
            AnthropicResponse response = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(AnthropicResponse.class);

            return toResult(response, request.model());
        } catch (RestClientException ex) {
            // Message métier neutre : ni la clé, ni la réponse brute du fournisseur ne remontent.
            log.warn("Appel au fournisseur IA en échec (modèle={})", request.model());
            throw new AIProviderException("Échec de l'appel au fournisseur IA.", ex);
        }
    }

    private List<Map<String, String>> toApiMessages(List<ChatMessage> messages) {
        List<Map<String, String>> apiMessages = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            apiMessages.add(Map.of(
                    "role", message.role() == ChatRole.ASSISTANT ? "assistant" : "user",
                    "content", message.content()));
        }
        return apiMessages;
    }

    private ChatCompletionResult toResult(AnthropicResponse response, String requestedModel) {
        if (response == null || response.content() == null || response.content().isEmpty()) {
            throw new AIProviderException("Réponse vide du fournisseur IA.");
        }
        String text = response.content().stream()
                .filter(block -> "text".equals(block.type()) && block.text() != null)
                .map(AnthropicResponse.ContentBlock::text)
                .reduce("", String::concat);
        if (text.isEmpty()) {
            throw new AIProviderException("Réponse sans contenu textuel du fournisseur IA.");
        }
        String model = response.model() != null ? response.model() : requestedModel;
        int input = response.usage() != null ? response.usage().inputTokens() : 0;
        int output = response.usage() != null ? response.usage().outputTokens() : 0;
        return new ChatCompletionResult(text, model, input, output);
    }
}
