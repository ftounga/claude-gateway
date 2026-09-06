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
 * {@code stop_reason}, {@code usage}) en {@link AgentTurn}. Le plafond de sortie est celui de l'agent
 * ({@code agent-max-tokens}), pas celui du chat : écrire un fichier consomme la sortie du modèle.
 *
 * <p><b>Cache de prompt</b> (F-39 / SF-39-01) : le préfixe stable de la requête — outils, consigne
 * système, historique déjà envoyé — porte des marqueurs {@code cache_control}. Sans eux, chaque
 * itération d'un tour renvoie tout au tarif plein et le volume facturé croît en N².</p> Le mapping fournisseur est confiné ici ;
 * le domaine reste neutre. La clé n'est jamais journalisée.
 */
@Component
public class AnthropicAgentProvider implements AiAgentProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAgentProvider.class);

    /** Marqueur de cache posé sur le dernier bloc d'un segment stable (F-39 / SF-39-01). */
    private static final Map<String, Object> CACHE_CONTROL = Map.of("type", "ephemeral");

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
        body.put("max_tokens", properties.agentMaxTokens());
        body.put("messages", toApiMessages(request.messages()));
        if (StringUtils.hasText(request.system())) {
            // Forme « liste de blocs » : seule forme qui accepte un `cache_control`. Le contenu est
            // inchangé. L'ordre de rendu du fournisseur étant tools -> system -> messages, ce
            // marqueur couvre AUSSI les définitions d'outils qui précèdent (SF-39-01, D1).
            body.put("system", List.of(cached(Map.of("type", "text", "text", request.system()))));
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

    /**
     * Traduit l'historique, en posant un marqueur de cache sur le <b>dernier bloc du dernier
     * message</b> (F-39 / SF-39-01).
     *
     * <p>Le marqueur glisse d'une itération à l'autre : celle qui l'écrit paie l'écriture une fois,
     * toutes les suivantes relisent le segment à une fraction du prix (D2). Sur un tour de 30
     * itérations, chaque segment est écrit une fois et relu jusqu'à 29 fois.</p>
     */
    private List<Map<String, Object>> toApiMessages(List<AgentMessage> messages) {
        List<Map<String, Object>> apiMessages = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            boolean last = i == messages.size() - 1;
            List<Map<String, Object>> blocks = new ArrayList<>(message.content().size());
            for (int j = 0; j < message.content().size(); j++) {
                Map<String, Object> block = toApiBlock(message.content().get(j));
                if (last && j == message.content().size() - 1) {
                    block = cached(block);
                }
                blocks.add(block);
            }
            apiMessages.add(Map.of("role", message.role(), "content", blocks));
        }
        return apiMessages;
    }

    /** Copie d'un bloc portant le marqueur de cache. Le bloc d'origine reste immuable. */
    private static Map<String, Object> cached(Map<String, Object> block) {
        Map<String, Object> marked = new HashMap<>(block);
        marked.put("cache_control", CACHE_CONTROL);
        return marked;
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
        // « Coupé au plafond » n'est pas « terminé » (SF-28-18) : le fournisseur n'attend plus rien de
        // nous, mais sa réponse s'arrête au milieu — souvent avant même le bloc `tool_use` annoncé par
        // la phrase qui précède. La distinguer ici est le seul endroit où l'information existe.
        boolean truncated = "max_tokens".equals(stopReason);
        JsonNode usage = response.path("usage");
        // Les tokens servis par le cache ne sont PAS dans `input_tokens` (SF-39-01, D3). Ne compter
        // que ce champ ferait chuter le décompte du quota d'environ 90 % sans que rien ne le
        // signale : le quota mesure ce qui a été TRAITÉ, pas ce que le fournisseur nous facture.
        int cacheCreation = usage.path("cache_creation_input_tokens").asInt(0);
        int cacheRead = usage.path("cache_read_input_tokens").asInt(0);
        int inputTokens = usage.path("input_tokens").asInt(0) + cacheCreation + cacheRead;
        int outputTokens = usage.path("output_tokens").asInt(0);
        // Un cache qui ne prend pas ne lève aucune erreur : seuls ces compteurs le disent.
        log.debug("Tour d'agent : {} tokens d'entrée (dont {} écrits en cache, {} lus en cache).",
                inputTokens, cacheCreation, cacheRead);
        return new AgentTurn(text.toString(), toolCalls, finished, inputTokens, outputTokens, truncated);
    }

    /** Clé BYOK fournie pour l'appel, sinon clé plateforme. Jamais journalisée. */
    private String resolveApiKey(String overrideApiKey) {
        if (StringUtils.hasText(overrideApiKey)) {
            return overrideApiKey;
        }
        return properties.apiKey();
    }
}
