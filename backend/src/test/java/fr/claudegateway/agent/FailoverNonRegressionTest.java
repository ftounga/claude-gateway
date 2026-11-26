package fr.claudegateway.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AnthropicProperties;
import fr.claudegateway.ai.AnthropicProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatMessage;
import fr.claudegateway.ai.ChatRole;
import fr.claudegateway.ai.bedrock.BedrockProperties;
import fr.claudegateway.ai.failover.FailoverAIProvider;
import fr.claudegateway.ai.failover.ProviderFailover;
import fr.claudegateway.ai.failover.ServedChannel;

/**
 * F-277 / SF-277-02 — non-régression exigée par le PO : quand Anthropic répond (mode {@code auto}), l'aiguillage
 * envoie <b>à l'octet près</b> la même requête (corps et en-têtes) qu'un appel direct, pour la boucle d'agent
 * (avec et sans flux) et pour le chat. La comparaison se fait sur ce qu'un vrai serveur HTTP local reçoit.
 */
class FailoverNonRegressionTest {

    private static final String JSON_OK = """
            {"model":"claude-opus-5-5","id":"msg_1","content":[{"type":"text","text":"ok"}],
             "stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":2}}""";
    private static final String SSE_OK = String.join("\n",
            "data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-opus-5-5\",\"id\":\"msg_2\","
                    + "\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}",
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
            "", "");

    /** Ce que le serveur a reçu : méthode, chemin, en-têtes triés, corps. */
    record Received(String method, String path, Map<String, List<String>> headers, byte[] body) {
    }

    private HttpServer server;
    private final List<Received> received = new ArrayList<>();
    /** Réponse servie : statut + corps ; SSE si la requête demande le flux. */
    private volatile int status = 200;
    private volatile String jsonResponse = JSON_OK;
    private volatile String errorBody = "";
    private final AtomicInteger bedrockCalls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), List.copyOf(v)));
            synchronized (received) {
                received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers,
                        body));
            }
            boolean stream = new String(body, StandardCharsets.UTF_8).contains("\"stream\":true");
            byte[] out;
            if (status != 200) {
                out = errorBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, out.length);
            } else if (stream) {
                out = SSE_OK.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, out.length);
            } else {
                out = jsonResponse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
            }
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        ServedChannel.clear();
    }

    private AnthropicProperties properties() {
        return new AnthropicProperties("sk-ant-platform-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "2023-06-01", null, null, 512, null, Duration.ofSeconds(10), Duration.ofSeconds(10), 1);
    }

    private ProviderFailover auto() {
        return new ProviderFailover(BedrockProperties.defaults().withMode("auto"), Clock.systemUTC());
    }

    private AnthropicAgentProvider anthropicAgent() {
        return new AnthropicAgentProvider(properties(), AgentApiFeaturesProperties.defaults(), RestClient.builder());
    }

    /** Canal Bedrock factice : compte ses appels, rend un tour neutre. */
    private AiAgentProvider fakeBedrockAgent() {
        return new AiAgentProvider() {
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request) {
                bedrockCalls.incrementAndGet();
                return lastAgentTurn;
            }

            @Override
            public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener listener) {
                bedrockCalls.incrementAndGet();
                listener.onTextDelta("bedrock");
                return lastAgentTurn;
            }
        };
    }

    /** Tour rendu par le canal Bedrock factice : un vrai tour décodé plus tôt dans le test. */
    private volatile AgentTurn lastAgentTurn;

    private static AgentTurnRequest atelierRequest() {
        return new AgentTurnRequest("claude-opus-5-5", "consigne stable",
                List.of(AgentMessage.userText("bonjour")),
                List.of(new AgentTool("run", "lance", Map.of("type", "object"))), null);
    }

    private List<Received> drain() {
        synchronized (received) {
            List<Received> copy = List.copyOf(received);
            received.clear();
            return copy;
        }
    }

    private static void assertSameRequest(Received direct, Received routed) {
        assertThat(routed.method()).isEqualTo(direct.method());
        assertThat(routed.path()).isEqualTo(direct.path());
        assertThat(routed.headers()).isEqualTo(direct.headers());
        assertThat(routed.body()).isEqualTo(direct.body());
    }

    @Test
    void agentNonStreamedRequestIsByteIdentical() {
        AnthropicAgentProvider direct = anthropicAgent();
        lastAgentTurn = direct.nextTurn(atelierRequest());
        Received viaDirect = drain().get(0);

        FailoverAgentProvider routed = FailoverAgentProvider.of(anthropicAgent(), fakeBedrockAgent(), auto());
        AgentTurn turn = routed.nextTurn(atelierRequest());
        Received viaRouter = drain().get(0);

        assertSameRequest(viaDirect, viaRouter);
        assertThat(turn).isEqualTo(lastAgentTurn);
        assertThat(bedrockCalls).hasValue(0);
        assertThat(new String(viaRouter.body(), StandardCharsets.UTF_8)).contains("\"diagnostics\"")
                .contains("web_search");
    }

    @Test
    void agentStreamedRequestAndDeltasAreIdentical() {
        AnthropicAgentProvider direct = anthropicAgent();
        StringBuilder screenDirect = new StringBuilder();
        lastAgentTurn = direct.nextTurn(atelierRequest(), screenDirect::append);
        Received viaDirect = drain().get(0);

        FailoverAgentProvider routed = FailoverAgentProvider.of(anthropicAgent(), fakeBedrockAgent(), auto());
        StringBuilder screenRouted = new StringBuilder();
        AgentTurn turn = routed.nextTurn(atelierRequest(), screenRouted::append);
        Received viaRouter = drain().get(0);

        assertSameRequest(viaDirect, viaRouter);
        assertThat(screenRouted).hasToString(screenDirect.toString()).hasToString("ok");
        assertThat(turn).isEqualTo(lastAgentTurn);
        assertThat(bedrockCalls).hasValue(0);
    }

    @Test
    void chatRequestsAreByteIdentical() {
        ObjectMapper mapper = new ObjectMapper();
        AIProvider direct = new AnthropicProvider(properties(), RestClient.builder(), mapper);
        ChatCompletionRequest request = new ChatCompletionRequest("claude-opus-5-5",
                List.of(new ChatMessage(ChatRole.USER, "bonjour")));
        var directResult = direct.complete(request);
        StringBuilder directScreen = new StringBuilder();
        var directStream = direct.streamComplete(request, directScreen::append);
        List<Received> viaDirect = drain();

        AIProvider routed = FailoverAIProvider.of(new AnthropicProvider(properties(), RestClient.builder(), mapper),
                failingIfCalled(), auto());
        var routedResult = routed.complete(request);
        StringBuilder routedScreen = new StringBuilder();
        var routedStream = routed.streamComplete(request, routedScreen::append);
        List<Received> viaRouter = drain();

        assertThat(viaRouter).hasSize(2);
        assertSameRequest(viaDirect.get(0), viaRouter.get(0));
        assertSameRequest(viaDirect.get(1), viaRouter.get(1));
        assertThat(routedResult).isEqualTo(directResult);
        assertThat(routedStream).isEqualTo(directStream);
        assertThat(routedScreen).hasToString(directScreen.toString());
    }

    @Test
    void anOrdinary400OrA413IsNotReplayed() {
        FailoverAgentProvider routed = FailoverAgentProvider.of(anthropicAgent(), fakeBedrockAgent(), auto());
        for (int code : new int[] { 400, 413 }) {
            status = code;
            errorBody = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad field\"}}";
            assertThatThrownBy(() -> routed.nextTurn(atelierRequest())).isInstanceOf(RuntimeException.class);
        }
        assertThat(bedrockCalls).hasValue(0);
    }

    @Test
    void aRefusalIsReturnedAsIsWithoutFailover() {
        // Un refus du modèle est une réponse 200 (stop_reason=refusal) : jamais une panne, jamais basculé.
        jsonResponse = "{\"model\":\"claude-opus-5-5\",\"content\":[],\"stop_reason\":\"refusal\","
                + "\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\"},"
                + "\"usage\":{\"input_tokens\":3,\"output_tokens\":0}}";
        FailoverAgentProvider routed = FailoverAgentProvider.of(anthropicAgent(), fakeBedrockAgent(), auto());
        AgentTurn turn = routed.nextTurn(atelierRequest());
        assertThat(turn.refused()).isTrue();
        assertThat(bedrockCalls).hasValue(0);
    }

    @Test
    void creditExhaustionIsReplayedOnBedrock() {
        lastAgentTurn = anthropicAgent().nextTurn(atelierRequest());
        drain();
        status = 400;
        errorBody = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Your credit "
                + "balance is too low to access the Anthropic API.\"}}";
        FailoverAgentProvider routed = FailoverAgentProvider.of(anthropicAgent(), fakeBedrockAgent(), auto());
        StringBuilder screen = new StringBuilder();

        AgentTurn turn = routed.nextTurn(atelierRequest(), screen::append);

        assertThat(turn).isSameAs(lastAgentTurn);
        assertThat(screen).hasToString("bedrock");
        assertThat(bedrockCalls).hasValue(1);
    }

    @Test
    void aFailureAfterTextWasShownIsNeverReplayed() {
        lastAgentTurn = anthropicAgent().nextTurn(atelierRequest());
        drain();
        // Le texte « ok » part à l'écran, puis l'appel échoue en 529 : le rejouer dupliquerait « ok ».
        FailoverAgentProvider routed = FailoverAgentProvider.of(new AnthropicAgentProvider(properties(),
                AgentApiFeaturesProperties.defaults(), RestClient.builder()) {
            @Override
            public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener listener) {
                super.nextTurn(request, listener);
                throw new AIProviderException("x", ProviderFailoverAccess.overloaded());
            }
        }, fakeBedrockAgent(), auto());
        StringBuilder screen = new StringBuilder();

        assertThatThrownBy(() -> routed.nextTurn(atelierRequest(), screen::append))
                .isInstanceOf(AIProviderException.class);
        assertThat(screen).hasToString("ok");
        assertThat(bedrockCalls).hasValue(0);
    }

    private AIProvider failingIfCalled() {
        return new AIProvider() {
            @Override
            public fr.claudegateway.ai.ChatCompletionResult complete(ChatCompletionRequest request) {
                throw new AssertionError("Bedrock ne doit pas être appelé");
            }

            @Override
            public fr.claudegateway.ai.ProviderFileReference uploadFile(fr.claudegateway.ai.ProviderFileUpload u) {
                throw new AssertionError("Bedrock ne doit pas être appelé");
            }
        };
    }

    /** Une réponse 529 de forme Anthropic. */
    static final class ProviderFailoverAccess {
        static org.springframework.web.client.RestClientResponseException overloaded() {
            return new org.springframework.web.client.RestClientResponseException("x",
                    org.springframework.http.HttpStatusCode.valueOf(529), "x",
                    new org.springframework.http.HttpHeaders(), "{}".getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        }
    }
}
