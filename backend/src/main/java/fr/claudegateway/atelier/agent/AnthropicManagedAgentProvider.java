package fr.claudegateway.atelier.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.ai.AnthropicProperties;

/**
 * Implémentation Anthropic de {@link ManagedAgentProvider} (F-28 / Phase 2, ADR-013). Réplique le
 * patron d'{@code AnthropicProvider} : {@link RestClient} sur {@code app.ai.anthropic.base-url}, clé
 * plateforme en en-tête {@code x-api-key}, {@code anthropic-version} + en-tête(s) beta. Le mapping
 * fournisseur est confiné ici ; le domaine ne dépend que de {@link ManagedAgentProvider}. La clé
 * n'est jamais journalisée.
 */
@Component
public class AnthropicManagedAgentProvider implements ManagedAgentProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicManagedAgentProvider.class);

    /** En-tête beta requis par l'API Managed Agents d'Anthropic (valeur documentée, non secrète). */
    static final String MANAGED_AGENTS_BETA = "managed-agents-2026-04-01";

    /** En-tête beta requis par la Files API d'Anthropic (valeur documentée, non secrète). */
    static final String FILES_API_BETA = "files-api-2025-04-14";

    /** Type d'outil « agent toolset » attendu par l'API Agents (valeur documentée). */
    private static final String AGENT_TOOLSET_TYPE = "agent_toolset_20260401";

    /** Borne de sécurité sur le nombre de pages d'events lues par tour de polling. */
    private static final int MAX_EVENT_PAGES = 1000;

    private final AnthropicProperties properties;
    private final AtelierAgentProperties agentProperties;
    private final RestClient restClient;

    public AnthropicManagedAgentProvider(AnthropicProperties properties,
            AtelierAgentProperties agentProperties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.agentProperties = agentProperties;
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public ManagedEnvironment createEnvironment(EnvironmentSpec spec) {
        Map<String, Object> body = Map.of(
                "name", spec.name(),
                "config", Map.of(
                        "type", "cloud",
                        "networking", Map.of(
                                "type", "limited",
                                "allow_package_managers", spec.allowPackageManagers())));

        JsonNode response = post("/v1/environments", body, "création de l'environnement");
        String id = text(response, "id");
        if (id == null || id.isBlank()) {
            throw new AgentProviderException("Réponse sans identifiant d'environnement du fournisseur d'agents.");
        }
        return new ManagedEnvironment(id);
    }

    @Override
    public ManagedAgentDefinition createAgent(AgentSpec spec) {
        Map<String, Object> body = Map.of(
                "name", spec.name(),
                "model", spec.model(),
                "system", spec.system(),
                "tools", List.of(Map.of(
                        "type", AGENT_TOOLSET_TYPE,
                        "default_config", Map.of("enabled", true))));

        JsonNode response = post("/v1/agents", body, "création de l'agent");
        String id = text(response, "id");
        String version = text(response, "version");
        if (id == null || id.isBlank() || version == null || version.isBlank()) {
            throw new AgentProviderException("Réponse sans identifiant/version d'agent du fournisseur d'agents.");
        }
        return new ManagedAgentDefinition(id, version);
    }

    @Override
    public String uploadFile(String filename, byte[] content) {
        // Part "file" du multipart : ByteArrayResource nommé pour porter le filename dans la requête.
        ByteArrayResource fileResource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new HttpEntity<>(fileResource, octetStreamHeaders()));
        parts.add("purpose", "agent");

        try {
            JsonNode response = restClient.post()
                    .uri("/v1/files")
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header("anthropic-beta", FILES_API_BETA)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(JsonNode.class);
            String id = text(response, "id");
            if (id == null || id.isBlank()) {
                throw new AgentProviderException("Réponse sans identifiant de fichier du fournisseur d'agents.");
            }
            return id;
        } catch (RestClientException ex) {
            log.warn("Téléversement de fichier au fournisseur d'agents en échec.");
            throw new AgentProviderException("Échec du téléversement de fichier au fournisseur d'agents.", ex);
        }
    }

    @Override
    public ManagedSession createSession(String agentId, String environmentId, List<FileMount> resources) {
        List<Map<String, Object>> mounts = new ArrayList<>();
        for (FileMount mount : resources) {
            mounts.add(Map.of(
                    "type", "file",
                    "file_id", mount.fileId(),
                    "mount_path", mount.mountPath()));
        }
        Map<String, Object> body = Map.of(
                "agent", agentId,
                "environment_id", environmentId,
                "resources", mounts);

        JsonNode response = post("/v1/sessions", body, "création de la session");
        String id = text(response, "id");
        if (id == null || id.isBlank()) {
            throw new AgentProviderException("Réponse sans identifiant de session du fournisseur d'agents.");
        }
        return new ManagedSession(id);
    }

    @Override
    public void sendUserMessage(String sessionId, String text) {
        // Forme attendue par l'API : un tableau `events`, chaque event portant un `content` en blocs.
        Map<String, Object> event = Map.of(
                "type", "user.message",
                "content", List.of(Map.of("type", "text", "text", text == null ? "" : text)));
        Map<String, Object> body = Map.of("events", List.of(event));
        post("/v1/sessions/" + sessionId + "/events", body, "envoi du message utilisateur");
    }

    @Override
    public SessionRun awaitCompletion(String sessionId, Duration timeout, int maxPolls) {
        // Délégation à la variante 4-args avec écouteur inerte : comportement historique inchangé.
        return awaitCompletion(sessionId, timeout, maxPolls, ManagedEventListener.NOOP);
    }

    @Override
    public SessionRun awaitCompletion(String sessionId, Duration timeout, int maxPolls,
            ManagedEventListener listener) {
        ManagedEventListener sink = listener == null ? ManagedEventListener.NOOP : listener;
        StringBuilder reply = new StringBuilder();
        Set<String> seen = new HashSet<>();
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        for (int poll = 0; poll < maxPolls; poll++) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AgentSessionTimeoutException(
                        "Délai d'attente dépassé sur la complétion de la session (timeout).");
            }
            // Les nouveaux events apparaissent sur la MÊME page tant que le total < limit : chaque tour
            // relit depuis la page 0 et avance jusqu'à une page vide (couvre > 1000 events). La
            // déduplication par id garantit qu'aucun event n'est traité/notifié deux fois.
            String stopReason = null;
            boolean idle = false;
            String cursor = null; // 1re page sans curseur ; ensuite `next_page` de la réponse
            for (int page = 0; page < MAX_EVENT_PAGES; page++) {
                JsonNode pageNode = readEventsPage(sessionId, cursor);
                for (JsonNode event : events(pageNode)) {
                    String id = text(event, "id");
                    if (id != null && !seen.add(id)) {
                        continue; // event déjà traité lors d'un tour précédent
                    }
                    String type = text(event, "type");
                    if ("agent.message".equals(type)) {
                        String fragment = extractText(event);
                        reply.append(fragment);
                        sink.onAgentText(fragment);
                    } else if ("agent.tool_use".equals(type) || "agent.custom_tool_use".equals(type)) {
                        sink.onAction(toolName(event), toolDetail(event));
                    } else if ("session.status_running".equals(type)) {
                        sink.onStatus("running");
                    } else if ("session.status_idle".equals(type)) {
                        idle = true;
                        stopReason = stopReason(event);
                        sink.onStatus("idle");
                    }
                }
                cursor = text(pageNode, "next_page");
                if (idle || cursor == null || cursor.isBlank()) {
                    break; // idle terminal, ou plus de page (fin des events courants)
                }
            }
            if (idle) {
                return new SessionRun(reply.toString(), stopReason);
            }
            sleepBetweenPolls();
        }
        throw new AgentSessionTimeoutException(
                "Nombre maximal de tours de polling atteint sans complétion de la session.");
    }

    /** Raison d'arrêt d'un {@code session.status_idle} : chaîne, ou objet {@code {type: ...}}. */
    private static String stopReason(JsonNode event) {
        JsonNode node = event == null ? null : event.get("stop_reason");
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isObject() ? node.path("type").asText(null) : node.asText(null);
    }

    @Override
    public List<OutputFile> listOutputs(String sessionId) {
        try {
            JsonNode response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/files")
                            .queryParam("scope_id", sessionId).build())
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    // Les deux bêtas sont requises : deux valeurs sur le même en-tête anthropic-beta.
                    .header("anthropic-beta", MANAGED_AGENTS_BETA, FILES_API_BETA)
                    .retrieve()
                    .body(JsonNode.class);
            List<OutputFile> outputs = new ArrayList<>();
            for (JsonNode file : dataArray(response)) {
                String id = text(file, "id");
                String filename = text(file, "filename");
                if (id != null && !id.isBlank()) {
                    outputs.add(new OutputFile(id, filename));
                }
            }
            return outputs;
        } catch (RestClientException ex) {
            log.warn("Liste des sorties de session en échec.");
            throw new AgentProviderException("Échec de la récupération des sorties de la session.", ex);
        }
    }

    @Override
    public byte[] downloadFile(String fileId) {
        try {
            byte[] content = restClient.get()
                    .uri("/v1/files/" + fileId + "/content")
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header("anthropic-beta", FILES_API_BETA)
                    .retrieve()
                    .body(byte[].class);
            return content == null ? new byte[0] : content;
        } catch (RestClientException ex) {
            log.warn("Téléchargement de fichier au fournisseur d'agents en échec.");
            throw new AgentProviderException("Échec du téléchargement de fichier au fournisseur d'agents.", ex);
        }
    }

    @Override
    public void terminateSession(String sessionId) {
        // Nettoyage best-effort : ne doit jamais faire échouer le run appelant.
        try {
            restClient.delete()
                    .uri("/v1/sessions/" + sessionId)
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header("anthropic-beta", MANAGED_AGENTS_BETA)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            log.debug("Terminaison best-effort de la session en échec (ignorée).");
        }
    }

    /** Lit une page d'events (polling) de la session. Extraite pour la testabilité. */
    private JsonNode readEventsPage(String sessionId, String pageCursor) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/v1/sessions/" + sessionId + "/events").queryParam("limit", 1000);
                        // `page` est un curseur opaque (jamais un entier) : absent sur la 1re page,
                        // puis alimenté par le `next_page` de la réponse précédente.
                        if (pageCursor != null && !pageCursor.isBlank()) {
                            uriBuilder.queryParam("page", pageCursor);
                        }
                        return uriBuilder.build();
                    })
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header("anthropic-beta", MANAGED_AGENTS_BETA)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            log.warn("Lecture des events de session en échec.");
            throw new AgentProviderException("Échec de la lecture des events de la session.", ex);
        }
    }

    /** Attente configurable entre deux tours de polling ({@code 0} en test → aucun sleep réel). */
    private void sleepBetweenPolls() {
        Duration delay = agentProperties.pollDelay();
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentProviderException("Attente de complétion interrompue.", ex);
        }
    }

    /**
     * Exécute un POST Managed Agents avec les en-têtes d'authentification et beta. Toute erreur
     * {@link RestClientException} (4xx/5xx, réseau) est convertie en {@link AgentProviderException}
     * avec un message neutre (jamais de clé ni de réponse brute).
     */
    private JsonNode post(String uri, Map<String, Object> body, String operation) {
        try {
            return restClient.post()
                    .uri(uri)
                    .header("x-api-key", properties.apiKey())
                    .header("anthropic-version", properties.version())
                    .header("anthropic-beta", MANAGED_AGENTS_BETA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            // Message neutre : ni la clé ni la réponse brute du fournisseur ne remontent.
            log.warn("Appel au fournisseur d'agents en échec ({})", operation);
            throw new AgentProviderException("Échec de l'appel au fournisseur d'agents.", ex);
        }
    }

    /** En-têtes de la part fichier du multipart (contenu binaire opaque). */
    private static HttpHeaders octetStreamHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return headers;
    }

    /** Events d'une page : nœud {@code data} (tableau) ou tableau racine ; sinon vide. */
    private static Iterable<JsonNode> events(JsonNode page) {
        if (page == null) {
            return List.of();
        }
        JsonNode data = page.get("data");
        if (data != null && data.isArray()) {
            return data;
        }
        return page.isArray() ? page : List.<JsonNode>of();
    }

    /** Éléments d'une réponse liste : nœud {@code data} (tableau) ou tableau racine ; sinon vide. */
    private static Iterable<JsonNode> dataArray(JsonNode response) {
        return events(response);
    }

    /**
     * Texte d'un event {@code agent.message} : concatène les fragments {@code text} du {@code content}
     * (tableau de blocs) ; tolère un {@code content} textuel simple et un repli via {@code message}.
     */
    private static String extractText(JsonNode event) {
        JsonNode content = event.get("content");
        if (content == null && event.get("message") != null) {
            content = event.get("message").get("content");
        }
        if (content == null) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode block : content) {
                JsonNode textNode = block.get("text");
                if (textNode != null && !textNode.isNull()) {
                    sb.append(textNode.asText());
                }
            }
        }
        return sb.toString();
    }

    /**
     * Nom de l'outil d'un event {@code agent.tool_use} : champ {@code name} (ou {@code tool_name} en
     * repli), sinon {@code "tool"} par défaut. Jamais {@code null}.
     */
    private static String toolName(JsonNode event) {
        String name = text(event, "name");
        if (name == null || name.isBlank()) {
            name = text(event, "tool_name");
        }
        return name == null || name.isBlank() ? "tool" : name;
    }

    /**
     * Courte description de l'action d'un event {@code agent.tool_use} : la commande {@code input.command}
     * (ex. bash) si présente, sinon la représentation compacte de {@code input}, sinon {@code ""}.
     * Aucun secret ne transite ici (seule l'action de l'agent est relayée).
     */
    private static String toolDetail(JsonNode event) {
        JsonNode input = event.get("input");
        if (input == null || input.isNull()) {
            return "";
        }
        JsonNode command = input.get("command");
        if (command != null && !command.isNull()) {
            return command.isTextual() ? command.asText() : command.toString();
        }
        return input.isTextual() ? input.asText() : input.toString();
    }

    /** Lit un champ texte non nul de la réponse, ou {@code null} si absent. */
    private static String text(JsonNode response, String field) {
        if (response == null) {
            return null;
        }
        JsonNode node = response.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
