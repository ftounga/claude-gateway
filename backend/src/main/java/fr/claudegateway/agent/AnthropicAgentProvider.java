package fr.claudegateway.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.AnthropicProperties;

/**
 * Implémentation Anthropic de {@link AiAgentProvider} (F-28) : relaie un tour à {@code POST /v1/messages}
 * avec {@code tools} + {@code system}, et traduit la réponse (blocs {@code text}/{@code tool_use},
 * {@code stop_reason}, {@code usage}) en {@link AgentTurn}. Le mapping fournisseur est confiné ici ;
 * le domaine reste neutre. La clé n'est jamais journalisée.
 */
@Component
public class AnthropicAgentProvider implements AiAgentProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAgentProvider.class);

    private final AnthropicProperties properties;
    private final RestClient restClient;

    public AnthropicAgentProvider(AnthropicProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public AgentTurn nextTurn(AgentTurnRequest request) {
        String apiKey = resolveApiKey(request.apiKey());
        if (apiKey == null || apiKey.isBlank()) {
            throw new AIProviderUnavailableException("Le fournisseur IA n'est pas configuré.");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", properties.maxTokens());
        body.put("messages", toApiMessages(request.messages()));
        if (StringUtils.hasText(request.system())) {
            body.put("system", request.system());
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", toApiTools(request.tools()));
        }

        try {
            JsonNode response = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", properties.version())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return toTurn(response);
        } catch (RestClientException ex) {
            // Message neutre : ni la clé ni la réponse brute du fournisseur ne remontent au client.
            log.warn("Appel agent au fournisseur IA en échec (modèle={})", request.model());
            throw new AIProviderException("Échec de l'appel au fournisseur IA.", ex);
        }
    }

    private List<Map<String, Object>> toApiTools(List<AgentTool> tools) {
        List<Map<String, Object>> apiTools = new ArrayList<>(tools.size());
        for (AgentTool tool : tools) {
            apiTools.add(Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "input_schema", tool.inputSchema()));
        }
        return apiTools;
    }

    private List<Map<String, Object>> toApiMessages(List<AgentMessage> messages) {
        List<Map<String, Object>> apiMessages = new ArrayList<>(messages.size());
        for (AgentMessage message : messages) {
            List<Map<String, Object>> blocks = new ArrayList<>(message.content().size());
            for (AgentContentBlock block : message.content()) {
                blocks.add(toApiBlock(block));
            }
            apiMessages.add(Map.of("role", message.role(), "content", blocks));
        }
        return apiMessages;
    }

    private Map<String, Object> toApiBlock(AgentContentBlock block) {
        return switch (block) {
            case AgentContentBlock.Text text -> Map.of("type", "text", "text", text.text());
            case AgentContentBlock.ToolUse use -> Map.of(
                    "type", "tool_use", "id", use.id(), "name", use.name(), "input", use.input());
            case AgentContentBlock.ToolResult result -> {
                Map<String, Object> map = new HashMap<>();
                map.put("type", "tool_result");
                map.put("tool_use_id", result.toolUseId());
                map.put("content", result.content());
                if (result.isError()) {
                    map.put("is_error", true);
                }
                yield map;
            }
        };
    }

    private AgentTurn toTurn(JsonNode response) {
        if (response == null || !response.hasNonNull("content")) {
            throw new AIProviderException("Réponse vide du fournisseur IA.");
        }
        StringBuilder text = new StringBuilder();
        List<AgentToolCall> toolCalls = new ArrayList<>();
        for (JsonNode block : response.get("content")) {
            String type = block.path("type").asText("");
            if ("text".equals(type)) {
                text.append(block.path("text").asText(""));
            } else if ("tool_use".equals(type)) {
                JsonNode input = block.path("input");
                toolCalls.add(new AgentToolCall(
                        block.path("id").asText(""),
                        block.path("name").asText(""),
                        input.isMissingNode() ? MissingNode.getInstance() : input));
            }
        }
        String stopReason = response.path("stop_reason").asText("");
        boolean finished = !"tool_use".equals(stopReason);
        JsonNode usage = response.path("usage");
        int inputTokens = usage.path("input_tokens").asInt(0);
        int outputTokens = usage.path("output_tokens").asInt(0);
        return new AgentTurn(text.toString(), toolCalls, finished, inputTokens, outputTokens);
    }

    /** Clé BYOK fournie pour l'appel, sinon clé plateforme. Jamais journalisée. */
    private String resolveApiKey(String overrideApiKey) {
        if (StringUtils.hasText(overrideApiKey)) {
            return overrideApiKey;
        }
        return properties.apiKey();
    }
}
