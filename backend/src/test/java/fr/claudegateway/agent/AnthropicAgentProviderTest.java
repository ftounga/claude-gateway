package fr.claudegateway.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import fr.claudegateway.ai.AnthropicProperties;

/**
 * Vérifie la traduction de la réponse du fournisseur en {@link AgentTurn} (F-28 / SF-28-18) : le
 * plafond de sortie employé est celui de l'<b>agent</b>, et un tour <b>coupé</b> au plafond est
 * reconnu comme tel au lieu d'être confondu avec un tour terminé.
 */
class AnthropicAgentProviderTest {

    private static final String URL = "https://api.anthropic.com/v1/messages";

    private MockRestServiceServer server;
    private AnthropicAgentProvider provider;

    private void build(Integer agentMaxTokens) {
        AnthropicProperties properties = new AnthropicProperties(
                "sk-ant-test-key", "https://api.anthropic.com", "2023-06-01",
                null, null, 4096, agentMaxTokens, Duration.ofSeconds(5));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new AnthropicAgentProvider(properties, builder);
    }

    private AgentTurn call() {
        return provider.nextTurn(new AgentTurnRequest("claude-model", "consigne",
                List.of(AgentMessage.userText("bonjour")), List.of(), null));
    }

    private void respondWith(String stopReason, String content) {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"content": %s, "stop_reason": "%s",
                         "usage": {"input_tokens": 10, "output_tokens": 20}}
                        """.formatted(content, stopReason), MediaType.APPLICATION_JSON));
    }

    @Test
    void marksTurnAsTruncatedWhenTheProviderHitTheOutputCap() {
        build(null);
        // Réponse réellement observée dans ce cas : la phrase d'intention est là, le `tool_use`
        // annoncé n'a jamais été émis.
        respondWith("max_tokens", """
                [{"type": "text", "text": "Je vais créer ce fichier."}]""");

        AgentTurn turn = call();

        assertThat(turn.truncated()).isTrue();
        assertThat(turn.finished()).isTrue();
        server.verify();
    }

    @Test
    void doesNotMarkNormalTurnsAsTruncated() {
        build(null);
        respondWith("end_turn", """
                [{"type": "text", "text": "Voilà."}]""");

        AgentTurn turn = call();

        assertThat(turn.truncated()).isFalse();
        assertThat(turn.finished()).isTrue();
        assertThat(turn.text()).isEqualTo("Voilà.");
    }

    @Test
    void doesNotMarkToolUseTurnsAsTruncated() {
        build(null);
        respondWith("tool_use", """
                [{"type": "tool_use", "id": "tu_1", "name": "read_file", "input": {"path": "a.txt"}}]""");

        AgentTurn turn = call();

        assertThat(turn.truncated()).isFalse();
        assertThat(turn.finished()).isFalse();
        assertThat(turn.toolCalls()).hasSize(1);
    }

    @Test
    void sendsTheAgentOutputCapAndNotTheChatOne() {
        build(null); // agent-max-tokens absent => défaut 16 384, alors que le chat reste à 4 096.
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.max_tokens").value(16_384))
                .andRespond(withSuccess("""
                        {"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                         "usage": {"input_tokens": 1, "output_tokens": 1}}
                        """, MediaType.APPLICATION_JSON));

        call();

        server.verify();
    }

    @Test
    void honoursAConfiguredAgentOutputCap() {
        build(32_000);
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.max_tokens").value(32_000))
                .andRespond(withSuccess("""
                        {"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                         "usage": {"input_tokens": 1, "output_tokens": 1}}
                        """, MediaType.APPLICATION_JSON));

        call();

        server.verify();
    }
}
