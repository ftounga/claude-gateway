package fr.claudegateway.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AnthropicProperties;
import fr.claudegateway.ai.bedrock.BedrockChannel;
import fr.claudegateway.ai.bedrock.BedrockEventStream;
import fr.claudegateway.ai.bedrock.BedrockProperties;

/**
 * F-277 / SF-277-01 : la boucle d'agent servie par le canal Bedrock — même {@link AnthropicAgentProvider}, même
 * lecture, enveloppe adaptée (URL du profil, signature, corps nettoyé, flux event-stream).
 */
class BedrockChannelAgentTest {

    private static final String INVOKE =
            "https://bedrock-runtime.eu-west-3.amazonaws.com/model/global.anthropic.claude-opus-5-5/invoke";
    private static final String STREAM = INVOKE + "-with-response-stream";

    private MockRestServiceServer server;
    private AnthropicAgentProvider provider;
    private final List<Long> waits = new ArrayList<>();

    private void build() {
        AnthropicProperties anthropic = new AnthropicProperties("sk-ant-platform", "https://api.anthropic.com",
                "2023-06-01", null, null, 4096, null, Duration.ofSeconds(5), Duration.ofSeconds(5), 3);
        BedrockChannel channel = BedrockChannel.withSigner(BedrockProperties.defaults(),
                (uri, headers, body) -> Map.of("Authorization", List.of("AWS4-HMAC-SHA256 Credential=TEST"),
                        "X-Amz-Date", List.of("20261126T000000Z")));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new AnthropicAgentProvider(channel.anthropicProperties(anthropic), AgentApiFeaturesProperties.defaults(),
                channel.restClientBuilder(builder), null, waits::add);
    }

    private static AgentTurnRequest request() {
        return new AgentTurnRequest("claude-opus-5-5", "consigne", List.of(AgentMessage.userText("bonjour")),
                List.of(new AgentTool("run", "lance", Map.of("type", "object"))), null);
    }

    @Test
    void servesANonStreamedTurnThroughTheInferenceProfile() {
        build();
        server.expect(requestTo(INVOKE))
                .andExpect(header("Authorization", "AWS4-HMAC-SHA256 Credential=TEST"))
                .andExpect(headerDoesNotExist("x-api-key"))
                .andExpect(headerDoesNotExist("anthropic-version"))
                .andExpect(jsonPath("$.model").doesNotExist())
                .andExpect(jsonPath("$.diagnostics").doesNotExist())
                .andExpect(jsonPath("$.anthropic_version").value("bedrock-2023-05-31"))
                .andExpect(jsonPath("$.tools.length()").value(1))
                .andExpect(jsonPath("$.tools[0].name").value("run"))
                .andExpect(jsonPath("$.system[0].cache_control.ttl").value("1h"))
                .andRespond(withSuccess("""
                        {"model":"claude-opus-5-5","id":"msg_bdrk_1","content":[
                           {"type":"text","text":"Je lance."},
                           {"type":"tool_use","id":"toolu_bdrk_1","name":"run","input":{"cmd":"ls"}}],
                         "stop_reason":"tool_use",
                         "usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":100,
                                  "cache_creation_input_tokens":20}}
                        """, MediaType.APPLICATION_JSON));

        AgentTurn turn = provider.nextTurn(request());

        assertThat(turn.text()).isEqualTo("Je lance.");
        assertThat(turn.toolCalls()).hasSize(1);
        assertThat(turn.toolCalls().get(0).input().path("cmd").asText()).isEqualTo("ls");
        assertThat(turn.cacheReadTokens()).isEqualTo(100);
        assertThat(turn.cacheWriteTokens()).isEqualTo(20);
        assertThat(turn.servedModel()).isEqualTo("claude-opus-5-5");
        server.verify();
    }

    @Test
    void readsTheEventStreamLikeTheAnthropicSse() {
        build();
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (String event : List.of(
                "{\"type\":\"message_start\",\"message\":{\"model\":\"claude-opus-5-5\",\"id\":\"msg_bdrk_2\","
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1,\"cache_read_input_tokens\":7}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Bon\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"jour\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":4}}",
                "{\"type\":\"message_stop\",\"amazon-bedrock-invocationMetrics\":{\"inputTokenCount\":10}}")) {
            frames.writeBytes(BedrockEventStream.chunk(event));
        }
        server.expect(requestTo(STREAM))
                .andExpect(header("Accept", "application/vnd.amazon.eventstream"))
                .andExpect(jsonPath("$.stream").doesNotExist())
                .andRespond(withSuccess(frames.toByteArray(), MediaType.parseMediaType("application/vnd.amazon.eventstream")));
        StringBuilder screen = new StringBuilder();

        AgentTurn turn = provider.nextTurn(request(), screen::append);

        assertThat(screen).hasToString("Bonjour");
        assertThat(turn.text()).isEqualTo("Bonjour");
        assertThat(turn.finished()).isTrue();
        assertThat(turn.outputTokens()).isEqualTo(4);
        assertThat(turn.cacheReadTokens()).isEqualTo(7);
        server.verify();
    }

    @Test
    void anAwsThrottlingIsRetriedLikeAnAnthropic429() {
        build();
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-amzn-ErrorType", "ThrottlingException:http://internal.amazon.com/coral/com.amazon.bedrock/");
        server.expect(requestTo(INVOKE)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"Too many requests\"}"));
        server.expect(requestTo(INVOKE)).andRespond(withSuccess("""
                {"model":"claude-opus-5-5","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """, MediaType.APPLICATION_JSON));

        AgentTurn turn = provider.nextTurn(request());

        assertThat(turn.text()).isEqualTo("ok");
        assertThat(waits).hasSize(1);
        server.verify();
    }

    @Test
    void aTooLongInputBecomesThePromptTooLongSignal() {
        build();
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-amzn-ErrorType", "ValidationException:http://internal.amazon.com/coral/com.amazon.bedrock/");
        server.expect(requestTo(INVOKE)).andRespond(withStatus(HttpStatus.BAD_REQUEST).headers(headers)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"Input is too long for requested model.\"}"));

        assertThatThrownBy(() -> provider.nextTurn(request())).isInstanceOf(AgentPromptTooLongException.class);
    }

    @Test
    void aRefusedFieldIsAProviderFailureWithTheAwsMessage() {
        build();
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-amzn-ErrorType", "AccessDeniedException:http://internal.amazon.com/coral/com.amazon.bedrock/");
        server.expect(requestTo(INVOKE)).andRespond(withStatus(HttpStatus.FORBIDDEN).headers(headers)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"not authorized\"}"));

        assertThatThrownBy(() -> provider.nextTurn(request())).isInstanceOf(AIProviderException.class);
    }
}
