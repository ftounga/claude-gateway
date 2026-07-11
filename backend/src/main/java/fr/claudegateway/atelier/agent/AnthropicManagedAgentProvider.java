package fr.claudegateway.atelier.agent;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.ai.AnthropicProperties;

/**
 * Implémentation Anthropic de {@link ManagedAgentProvider} (F-28 / Phase 2, ADR-013). Réplique le
 * patron d'{@code AnthropicProvider} : {@link RestClient} sur {@code app.ai.anthropic.base-url}, clé
 * plateforme en en-tête {@code x-api-key}, {@code anthropic-version} + en-tête beta Managed Agents.
 * Le mapping fournisseur est confiné ici ; le domaine ne dépend que de {@link ManagedAgentProvider}.
 * La clé n'est jamais journalisée.
 */
@Component
public class AnthropicManagedAgentProvider implements ManagedAgentProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicManagedAgentProvider.class);

    /** En-tête beta requis par l'API Managed Agents d'Anthropic (valeur documentée, non secrète). */
    static final String MANAGED_AGENTS_BETA = "managed-agents-2026-04-01";

    /** Type d'outil « agent toolset » attendu par l'API Agents (valeur documentée). */
    private static final String AGENT_TOOLSET_TYPE = "agent_toolset_20260401";

    private final AnthropicProperties properties;
    private final RestClient restClient;

    public AnthropicManagedAgentProvider(AnthropicProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
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

    /** Lit un champ texte non nul de la réponse, ou {@code null} si absent. */
    private static String text(JsonNode response, String field) {
        if (response == null) {
            return null;
        }
        JsonNode node = response.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
