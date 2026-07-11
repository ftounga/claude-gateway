package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import fr.claudegateway.ai.AnthropicProperties;

/**
 * Vérifie {@link AnthropicManagedAgentProvider} contre un serveur simulé ({@link MockRestServiceServer}) :
 * endpoints, en-têtes (dont {@code anthropic-beta}), parsing des identifiants, et traduction des
 * erreurs en {@link AgentProviderException}.
 */
class AnthropicManagedAgentProviderTest {

    private static final String BETA = "managed-agents-2026-04-01";

    private MockRestServiceServer server;
    private AnthropicManagedAgentProvider provider;

    @BeforeEach
    void setUp() {
        AnthropicProperties properties = new AnthropicProperties(
                "sk-ant-test-key", "https://api.anthropic.com", "2023-06-01",
                null, null, null, Duration.ofSeconds(5));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new AnthropicManagedAgentProvider(properties, builder);
    }

    @Test
    void createEnvironmentPostsWithBetaHeaderAndParsesId() {
        server.expect(requestTo("https://api.anthropic.com/v1/environments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "sk-ant-test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(header("anthropic-beta", BETA))
                .andExpect(jsonPath("$.name").value("atelier-env"))
                .andExpect(jsonPath("$.config.type").value("cloud"))
                .andExpect(jsonPath("$.config.networking.type").value("limited"))
                .andExpect(jsonPath("$.config.networking.allow_package_managers").value(true))
                .andRespond(withSuccess("{\"id\":\"env_123\"}", MediaType.APPLICATION_JSON));

        ManagedEnvironment environment = provider.createEnvironment(new EnvironmentSpec("atelier-env", true));

        assertThat(environment.id()).isEqualTo("env_123");
        server.verify();
    }

    @Test
    void createAgentPostsWithToolsetAndParsesIdAndVersion() {
        server.expect(requestTo("https://api.anthropic.com/v1/agents"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("anthropic-beta", BETA))
                .andExpect(jsonPath("$.name").value("atelier-agent"))
                .andExpect(jsonPath("$.model").value("claude-opus-4-8"))
                .andExpect(jsonPath("$.system").value("Tu es utile."))
                .andExpect(jsonPath("$.tools[0].type").value("agent_toolset_20260401"))
                .andExpect(jsonPath("$.tools[0].default_config.enabled").value(true))
                .andRespond(withSuccess(
                        "{\"id\":\"agent_456\",\"version\":\"v1\"}", MediaType.APPLICATION_JSON));

        ManagedAgentDefinition agent = provider.createAgent(
                new AgentSpec("atelier-agent", "claude-opus-4-8", "Tu es utile."));

        assertThat(agent.id()).isEqualTo("agent_456");
        assertThat(agent.version()).isEqualTo("v1");
        server.verify();
    }

    @Test
    void createEnvironmentTranslatesServerErrorToAgentProviderException() {
        server.expect(requestTo("https://api.anthropic.com/v1/environments"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> provider.createEnvironment(new EnvironmentSpec("atelier-env", true)))
                .isInstanceOf(AgentProviderException.class);
        server.verify();
    }

    @Test
    void createAgentTranslatesClientErrorToAgentProviderException() {
        server.expect(requestTo("https://api.anthropic.com/v1/agents"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> provider.createAgent(
                new AgentSpec("atelier-agent", "claude-opus-4-8", "Tu es utile.")))
                .isInstanceOf(AgentProviderException.class);
        server.verify();
    }
}
