package fr.claudegateway.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                "messages", toApiMessages(request.messages(), request.attachments()));

        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", properties.version())
                    .contentType(MediaType.APPLICATION_JSON);
            // Les références de fichiers ({type:file}) nécessitent l'en-tête beta Files API.
            if (!request.attachments().isEmpty()) {
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

    private List<Map<String, Object>> toApiMessages(List<ChatMessage> messages, List<ProviderAttachment> attachments) {
        List<Map<String, Object>> apiMessages = new ArrayList<>(messages.size());
        int lastIndex = messages.size() - 1;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            String role = message.role() == ChatRole.ASSISTANT ? "assistant" : "user";
            // Les pièces jointes sont rattachées au dernier message (le tour utilisateur courant).
            boolean attachHere = i == lastIndex && message.role() != ChatRole.ASSISTANT && !attachments.isEmpty();
            Object content = attachHere
                    ? toContentBlocks(message.content(), attachments)
                    : message.content();
            apiMessages.add(Map.of("role", role, "content", content));
        }
        return apiMessages;
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
