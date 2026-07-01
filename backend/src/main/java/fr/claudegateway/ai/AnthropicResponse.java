package fr.claudegateway.ai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Vue partielle de la réponse de l'API Anthropic {@code POST /v1/messages} nécessaire au proxy.
 * Interne à la couche fournisseur : non exposée au reste de l'application.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record AnthropicResponse(
        String model,
        List<ContentBlock> content,
        Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentBlock(String type, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens) {
    }
}
