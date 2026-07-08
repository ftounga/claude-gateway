package fr.claudegateway.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;
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

    /** En-tête beta requis par la Files API d'Anthropic (valeur documentée, non secrète). */
    private static final String FILES_API_BETA = "files-api-2025-04-14";

    private final AnthropicProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AnthropicProvider(AnthropicProperties properties, RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
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
        // Clé à utiliser : celle de l'utilisateur (BYOK) si fournie, sinon la clé plateforme (Hosted).
        // Provider-neutre : la Gateway ne couple pas le domaine à Anthropic ; la clé n'est jamais loggée.
        String apiKey = resolveApiKey(request.apiKey());
        if (apiKey == null || apiKey.isBlank()) {
            // Aucune clé (ni BYOK ni plateforme) => fournisseur dormant. La clé n'est jamais journalisée.
            throw new AIProviderUnavailableException("Le fournisseur IA n'est pas configuré.");
        }

        Map<String, Object> body = Map.of(
                "model", request.model(),
                "max_tokens", properties.maxTokens(),
                "messages", toApiMessages(request.messages()));

        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", properties.version())
                    .contentType(MediaType.APPLICATION_JSON);
            // Les références de fichiers ({type:file}) nécessitent l'en-tête beta Files API.
            if (hasAttachments(request.messages())) {
                spec = spec.header("anthropic-beta", FILES_API_BETA);
            }

            AnthropicResponse response = spec
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

    @Override
    public ChatCompletionResult streamComplete(ChatCompletionRequest request, Consumer<String> onDelta) {
        String apiKey = resolveApiKey(request.apiKey());
        if (apiKey == null || apiKey.isBlank()) {
            throw new AIProviderUnavailableException("Le fournisseur IA n'est pas configuré.");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", properties.maxTokens());
        body.put("messages", toApiMessages(request.messages()));
        body.put("stream", true);

        StringBuilder text = new StringBuilder();
        // [0] = input tokens (message_start), [1] = output tokens (message_delta).
        int[] usage = new int[2];

        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", properties.version())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM);
            if (hasAttachments(request.messages())) {
                spec = spec.header("anthropic-beta", FILES_API_BETA);
            }

            spec.body(body).exchange((clientRequest, clientResponse) -> {
                if (clientResponse.getStatusCode().isError()) {
                    throw new AIProviderException(
                            "Échec de l'appel au fournisseur IA (statut "
                                    + clientResponse.getStatusCode().value() + ").", null);
                }
                readSseStream(clientResponse.getBody(), onDelta, text, usage);
                return null;
            });
        } catch (AIProviderException ex) {
            log.warn("Streaming du fournisseur IA en échec (modèle={})", request.model());
            throw ex;
        } catch (RestClientException ex) {
            log.warn("Streaming du fournisseur IA en échec (modèle={})", request.model());
            throw new AIProviderException("Échec de l'appel au fournisseur IA.", ex);
        }

        return new ChatCompletionResult(text.toString(), request.model(), usage[0], usage[1]);
    }

    /**
     * Lit le flux SSE d'Anthropic : accumule le texte des {@code text_delta}, relaie chaque fragment
     * via {@code onDelta} et capture l'usage. Toute erreur de lecture/parse est convertie en
     * {@link AIProviderException} (message neutre, jamais de contenu brut fournisseur).
     */
    private void readSseStream(java.io.InputStream in, Consumer<String> onDelta, StringBuilder text,
            int[] usage) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String json = line.substring("data:".length()).trim();
                if (json.isEmpty() || "[DONE]".equals(json)) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(json);
                switch (node.path("type").asText()) {
                    case "content_block_delta" -> {
                        JsonNode delta = node.path("delta");
                        if ("text_delta".equals(delta.path("type").asText())) {
                            String chunk = delta.path("text").asText("");
                            if (!chunk.isEmpty()) {
                                text.append(chunk);
                                onDelta.accept(chunk);
                            }
                        }
                    }
                    case "message_start" ->
                        usage[0] = node.path("message").path("usage").path("input_tokens").asInt(usage[0]);
                    case "message_delta" ->
                        usage[1] = node.path("usage").path("output_tokens").asInt(usage[1]);
                    default -> {
                        // Autres événements (ping, content_block_start/stop, message_stop) : ignorés.
                    }
                }
            }
        } catch (IOException ex) {
            throw new AIProviderException("Échec de lecture du flux du fournisseur IA.", ex);
        }
    }

    @Override
    public ProviderFileReference uploadFile(ProviderFileUpload upload) {
        if (!properties.isConfigured()) {
            throw new AIProviderUnavailableException("Le fournisseur IA n'est pas configuré.");
        }

        // Part "file" du multipart : ByteArrayResource nommé pour porter le filename dans la requête.
        ByteArrayResource fileResource = new ByteArrayResource(upload.content()) {
            @Override
            public String getFilename() {
                return upload.filename();
            }
        };
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new HttpEntity<>(fileResource, fileHeaders(upload.mediaType())));

        try {
            AnthropicFileResponse response = restClient.post()
                    .uri("/v1/files")
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header("anthropic-beta", FILES_API_BETA)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(AnthropicFileResponse.class);

            if (response == null || response.id() == null || response.id().isBlank()) {
                throw new AIProviderException("Réponse sans identifiant de fichier du fournisseur IA.");
            }
            return new ProviderFileReference(response.id());
        } catch (RestClientException ex) {
            // Message neutre : ni la clé, ni la réponse brute du fournisseur ne remontent au client.
            log.warn("Transmission de fichier au fournisseur IA en échec (type={})", upload.mediaType());
            throw new AIProviderException("Échec de la transmission du fichier au fournisseur IA.", ex);
        }
    }

    /** Clé BYOK de l'appel si fournie (non vide), sinon la clé plateforme (mode Hosted). */
    private String resolveApiKey(String overrideApiKey) {
        if (overrideApiKey != null && !overrideApiKey.isBlank()) {
            return overrideApiKey;
        }
        return properties.apiKey();
    }

    private static org.springframework.http.HttpHeaders fileHeaders(String mediaType) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mediaType));
        return headers;
    }

    private List<Map<String, Object>> toApiMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> apiMessages = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            String role = message.role() == ChatRole.ASSISTANT ? "assistant" : "user";
            // Chaque message ré-embarque SES propres pièces jointes (F-25) : elles restent donc dans
            // le contexte à chaque tour, pas seulement au tour où elles ont été jointes.
            Object content = message.attachments().isEmpty()
                    ? message.content()
                    : toContentBlocks(message.content(), message.attachments());
            apiMessages.add(Map.of("role", role, "content", content));
        }
        return apiMessages;
    }

    /** Vrai si au moins un message porte une pièce jointe (⇒ en-tête beta Files API requis). */
    private static boolean hasAttachments(List<ChatMessage> messages) {
        return messages.stream().anyMatch(message -> !message.attachments().isEmpty());
    }

    /** Construit le contenu multi-blocs : le texte utilisateur suivi des blocs fichier. */
    private static List<Map<String, Object>> toContentBlocks(String text, List<ProviderAttachment> attachments) {
        List<Map<String, Object>> blocks = new ArrayList<>(attachments.size() + 1);
        blocks.add(Map.of("type", "text", "text", text));
        for (ProviderAttachment attachment : attachments) {
            String blockType = attachment.mediaType() != null
                    && attachment.mediaType().toLowerCase().startsWith("image/")
                    ? "image" : "document";
            blocks.add(Map.of(
                    "type", blockType,
                    "source", Map.of("type", "file", "file_id", attachment.providerFileId())));
        }
        return blocks;
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
