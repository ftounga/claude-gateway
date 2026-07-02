package fr.claudegateway.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * Vérifie le parsing du flux SSE d'Anthropic par {@link AnthropicProvider#streamComplete} : les
 * {@code text_delta} sont relayés dans l'ordre et concaténés, l'usage est capturé, et une erreur amont
 * lève {@link AIProviderException}. Un {@link HttpServer} local (JDK, sans dépendance) simule le flux.
 */
class AnthropicProviderStreamTest {

    private static final String SSE_BODY = String.join("\n",
            "event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":11}}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Bon\"}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"jour\"}}",
            "",
            "event: message_delta",
            "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}",
            "",
            "event: message_stop",
            "data: {\"type\":\"message_stop\"}",
            "");

    @Test
    void relaysTextDeltasAccumulatesTextAndCapturesUsage() throws IOException {
        HttpServer server = startServer(200, SSE_BODY);
        try {
            AnthropicProvider provider = providerFor(server);
            List<String> deltas = new ArrayList<>();

            ChatCompletionResult result = provider.streamComplete(
                    new ChatCompletionRequest("claude-opus-4-8",
                            List.of(new ChatMessage(ChatRole.USER, "Salut"))),
                    deltas::add);

            assertThat(deltas).containsExactly("Bon", "jour");
            assertThat(result.content()).isEqualTo("Bonjour");
            assertThat(result.inputTokens()).isEqualTo(11);
            assertThat(result.outputTokens()).isEqualTo(5);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void throwsProviderExceptionOnUpstreamError() throws IOException {
        HttpServer server = startServer(500, "{\"error\":\"boom\"}");
        try {
            AnthropicProvider provider = providerFor(server);

            assertThatThrownBy(() -> provider.streamComplete(
                    new ChatCompletionRequest("claude-opus-4-8",
                            List.of(new ChatMessage(ChatRole.USER, "Salut"))),
                    delta -> { }))
                    .isInstanceOf(AIProviderException.class);
        } finally {
            server.stop(0);
        }
    }

    private AnthropicProvider providerFor(HttpServer server) {
        AnthropicProperties properties = new AnthropicProperties(
                "sk-test-key", "http://localhost:" + server.getAddress().getPort(),
                null, null, null, 1024, Duration.ofSeconds(5));
        return new AnthropicProvider(properties, RestClient.builder(), new ObjectMapper());
    }

    private HttpServer startServer(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return server;
    }
}
