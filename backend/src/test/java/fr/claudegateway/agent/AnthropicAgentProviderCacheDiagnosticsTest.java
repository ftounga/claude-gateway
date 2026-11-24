package fr.claudegateway.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import fr.claudegateway.ai.AnthropicProperties;

/**
 * F-276 / SF-276-01 — le diagnostic de cache du fournisseur est demandé à chaque appel, chaîné à la
 * réponse précédente, et lu de la même façon en flux et hors flux ; le raisonnement envoyé est compté.
 */
class AnthropicAgentProviderCacheDiagnosticsTest {

    private static final String URL = "https://api.anthropic.com/v1/messages";

    private MockRestServiceServer server;
    private AnthropicAgentProvider provider;

    private void build(Boolean cacheDiagnostics) {
        AnthropicProperties properties = new AnthropicProperties(
                "sk-ant-test-key", "https://api.anthropic.com", "2023-06-01",
                null, null, 4096, null, Duration.ofSeconds(5), Duration.ofSeconds(5), 3);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AgentApiFeaturesProperties features = new AgentApiFeaturesProperties(null, null, null, null, null, null,
                null, null, null, null, null, null, null, cacheDiagnostics);
        provider = new AnthropicAgentProvider(properties, features, builder, null, w -> { });
    }

    private static AgentTurnRequest request(String previousId, List<AgentMessage> messages) {
        return new AgentTurnRequest("claude-opus-5-5", "consigne", messages, List.of(), null)
                .withPreviousResponseId(previousId);
    }

    private static final String JSON_RESPONSE = """
            {"id": "msg_2", "model": "claude-opus-5-5",
             "content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
             "diagnostics": {"cache_miss_reason": {"type": "messages_changed", "cache_missed_input_tokens": 23000}},
             "input_transformations": [
               {"type": "thinking_dropped", "path": "messages.1.content.0", "reason": "prefix_binding_mismatch"}],
             "usage": {"input_tokens": 10, "output_tokens": 2}}
            """;

    @Test
    void chainsThePreviousResponseAndReadsTheDiagnosis() {
        build(null);
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.diagnostics.previous_message_id").value("msg_1"))
                .andRespond(withSuccess(JSON_RESPONSE, MediaType.APPLICATION_JSON));

        AgentTurn turn = provider.nextTurn(request("msg_1", List.of(
                AgentMessage.userText("question"),
                AgentMessage.assistant(List.of(new AgentContentBlock.Reasoning("pensée", "sig"),
                        new AgentContentBlock.RedactedReasoning("x"), new AgentContentBlock.Text("réponse"))),
                AgentMessage.userText("suite"))));

        server.verify();
        assertThat(turn.diagnostics().responseId()).isEqualTo("msg_2");
        assertThat(turn.diagnostics().cacheMissReason()).isEqualTo("messages_changed");
        assertThat(turn.diagnostics().cacheMissedTokens()).isEqualTo(23_000L);
        assertThat(turn.diagnostics().thinkingReplayed()).isEqualTo(2);
        assertThat(turn.droppedThinkingBlocks()).isEqualTo(1);
    }

    @Test
    void firstCallOptsInWithANullPreviousId() {
        build(null);
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.diagnostics").exists())
                .andExpect(jsonPath("$.diagnostics.previous_message_id").value(Matchers.nullValue()))
                .andRespond(withSuccess("""
                        {"id": "msg_1", "content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                         "diagnostics": null, "usage": {"input_tokens": 10, "output_tokens": 2}}
                        """, MediaType.APPLICATION_JSON));

        AgentTurn turn = provider.nextTurn(request(null, List.of(AgentMessage.userText("bonjour"))));

        server.verify();
        assertThat(turn.diagnostics().cacheMissReason()).isEqualTo("none");
        assertThat(turn.diagnostics().cacheMissedTokens()).isNull();
        assertThat(turn.diagnostics().thinkingReplayed()).isZero();
    }

    @Test
    void anUnfinishedComparisonIsPending() {
        build(null);
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"id": "msg_3", "content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                 "diagnostics": {"cache_miss_reason": null}, "usage": {"input_tokens": 1, "output_tokens": 1}}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.nextTurn(request("msg_2", List.of(AgentMessage.userText("x"))))
                .diagnostics().cacheMissReason()).isEqualTo("pending");
    }

    @Test
    void theCircuitBreakerSendsTheRequestOfBefore() {
        build(false);
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.diagnostics").doesNotExist())
                .andRespond(withSuccess(JSON_RESPONSE, MediaType.APPLICATION_JSON));

        AgentTurn turn = provider.nextTurn(request("msg_1", List.of(AgentMessage.userText("x"))));

        server.verify();
        // Rien n'a été demandé : aucune cause n'est inventée, l'identifiant reste lisible.
        assertThat(turn.diagnostics().cacheMissReason()).isNull();
        assertThat(turn.diagnostics().responseId()).isEqualTo("msg_2");
    }

    @Test
    void streamedAndCompleteResponsesSayTheSame() {
        build(null);
        String sse = String.join("\n",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\",\"model\":\"claude-opus-5-5\","
                        + "\"diagnostics\":{\"cache_miss_reason\":{\"type\":\"messages_changed\","
                        + "\"cache_missed_input_tokens\":23000}},"
                        + "\"input_transformations\":[{\"type\":\"thinking_dropped\",\"path\":\"messages.1.content.0\","
                        + "\"reason\":\"prefix_binding_mismatch\"}],"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}",
                "",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}",
                "",
                "data: {\"type\":\"content_block_stop\",\"index\":0}",
                "",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}",
                "",
                "data: {\"type\":\"message_stop\"}",
                "");
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.diagnostics.previous_message_id").value("msg_1"))
                .andRespond(withSuccess(sse, MediaType.TEXT_EVENT_STREAM));
        List<String> pieces = new ArrayList<>();

        AgentTurn streamed = provider.nextTurn(request("msg_1", List.of(AgentMessage.userText("x"))), pieces::add);

        server.verify();
        assertThat(streamed.diagnostics().responseId()).isEqualTo("msg_2");
        assertThat(streamed.diagnostics().cacheMissReason()).isEqualTo("messages_changed");
        assertThat(streamed.diagnostics().cacheMissedTokens()).isEqualTo(23_000L);
        assertThat(streamed.droppedThinkingBlocks()).isEqualTo(1);
    }

    @Test
    void countsOnlyReasoningBlocksSent() {
        Map<String, Object> body = Map.of("messages", List.of(
                Map.of("role", "user", "content", List.of(Map.of("type", "text", "text", "a"))),
                Map.of("role", "assistant", "content", List.of(Map.of("type", "thinking", "thinking", ""),
                        Map.of("type", "text", "text", "b"), Map.of("type", "tool_use", "id", "t"))),
                Map.of("role", "assistant", "content", List.of(Map.of("type", "redacted_thinking", "data", "z")))));

        assertThat(AnthropicAgentProvider.thinkingBlocksSent(body)).isEqualTo(2);
        assertThat(AnthropicAgentProvider.thinkingBlocksSent(Map.of())).isZero();
    }
}
