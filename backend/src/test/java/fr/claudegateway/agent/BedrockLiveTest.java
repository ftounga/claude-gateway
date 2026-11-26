package fr.claudegateway.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.ai.AnthropicProperties;
import fr.claudegateway.ai.AnthropicProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ChatMessage;
import fr.claudegateway.ai.ChatRole;
import fr.claudegateway.ai.bedrock.BedrockChannel;
import fr.claudegateway.ai.bedrock.BedrockProperties;

/**
 * F-277 / SF-277-01 — vrais appels Bedrock, à quelques centimes (Haiku 5.5). <b>Manuel</b> : ne tourne qu'avec
 * {@code BEDROCK_LIVE_TEST=1} et des identifiants AWS ({@code AWS_PROFILE}). Jamais dans la suite courante.
 */
@EnabledIfEnvironmentVariable(named = "BEDROCK_LIVE_TEST", matches = "1")
class BedrockLiveTest {

    private static final String MODEL = "claude-haiku-5-5";

    private final AnthropicProperties anthropic = new AnthropicProperties("unused", null, null, null, null, 200, 200,
            Duration.ofSeconds(60), Duration.ofSeconds(60), 2);
    private final BedrockChannel channel = new BedrockChannel(BedrockProperties.defaults());

    @Test
    void agentTurnNonStreamedAndStreamed() {
        AnthropicAgentProvider agent = new AnthropicAgentProvider(channel.anthropicProperties(anthropic),
                AgentApiFeaturesProperties.defaults(), channel.restClientBuilder(RestClient.builder()));
        AgentTurnRequest request = new AgentTurnRequest(MODEL, "Réponds en trois mots.",
                List.of(AgentMessage.userText("Dis bonjour.")), List.of(), null);

        AgentTurn plain = agent.nextTurn(request);
        StringBuilder screen = new StringBuilder();
        AgentTurn streamed = agent.nextTurn(request, screen::append);

        assertThat(plain.text()).isNotBlank();
        assertThat(plain.servedModel()).isEqualTo(MODEL);
        assertThat(streamed.text()).isNotBlank();
        assertThat(screen.toString()).isEqualTo(streamed.text());
        assertThat(streamed.outputTokens()).isPositive();
    }

    /**
     * Le corps réel de l'Atelier sur Opus 5.5 : outils serveur, diagnostic, repli serveur, liaison du raisonnement,
     * notes de progression et effort explicite sont tous demandés — le canal doit les nettoyer et aboutir.
     */
    @Test
    void fullAtelierBodyOnOpus55IsAccepted() {
        AnthropicAgentProvider agent = new AnthropicAgentProvider(channel.anthropicProperties(anthropic),
                AgentApiFeaturesProperties.defaults(), channel.restClientBuilder(RestClient.builder()));
        AgentTurnRequest request = new AgentTurnRequest("claude-opus-5-5", "Réponds en un mot.",
                List.of(AgentMessage.userText("Dis oui.")),
                List.of(new AgentTool("noter", "note un fait", java.util.Map.of("type", "object",
                        "properties", java.util.Map.of("fait", java.util.Map.of("type", "string"))))), null);

        StringBuilder screen = new StringBuilder();
        AgentTurn turn = agent.nextTurn(request, screen::append);

        assertThat(turn.servedModel()).isEqualTo("claude-opus-5-5");
        assertThat(turn.outputTokens()).isPositive();
    }

    @Test
    void chatCompletionNonStreamedAndStreamed() {
        AnthropicProvider chat = new AnthropicProvider(channel.anthropicProperties(anthropic),
                channel.restClientBuilder(RestClient.builder()), new ObjectMapper());
        ChatCompletionRequest request = new ChatCompletionRequest(MODEL,
                List.of(new ChatMessage(ChatRole.USER, "Dis oui, en un mot.")));

        ChatCompletionResult plain = chat.complete(request);
        StringBuilder screen = new StringBuilder();
        ChatCompletionResult streamed = chat.streamComplete(request, screen::append);

        assertThat(plain.content()).isNotBlank();
        assertThat(streamed.content()).isEqualTo(screen.toString()).isNotBlank();
        assertThat(streamed.outputTokens()).isPositive();
    }
}
