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

import com.fasterxml.jackson.databind.JsonNode;

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

    // ------------------------------------------------- SF-39-01 : cache de prompt et comptage

    /** Capture le corps envoyé au fournisseur pour l'inspecter bloc à bloc. */
    private JsonNode captureBody() {
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> captured.set(
                        ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess("""
                        {"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                         "usage": {"input_tokens": 1, "output_tokens": 1}}
                        """, MediaType.APPLICATION_JSON));
        provider.nextTurn(new AgentTurnRequest("claude-model", "consigne de projet",
                List.of(AgentMessage.userText("bonjour")),
                List.of(new AgentTool("read_file", "Lit un fichier",
                        java.util.Map.of("type", "object"))),
                null));
        server.verify();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(captured.get());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void marksTheSystemBlockForCaching() {
        build(null);
        JsonNode body = captureBody();

        // Le système passe en liste de blocs — seule forme qui accepte un cache_control — et son
        // texte est inchangé. Ce marqueur couvre aussi les outils, rendus avant lui.
        JsonNode system = body.get("system");
        assertThat(system.isArray()).isTrue();
        assertThat(system.get(0).get("text").asText()).isEqualTo("consigne de projet");
        assertThat(system.get(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    void marksTheLastBlockOfTheLastMessageForCaching() {
        build(null);
        JsonNode body = captureBody();

        JsonNode messages = body.get("messages");
        JsonNode lastBlock = messages.get(messages.size() - 1).get("content").get(0);
        assertThat(lastBlock.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    void staysWithinTheProviderBreakpointLimit() {
        build(null);
        String body = captureBody().toString();

        // Le fournisseur en accepte 4 au plus ; on en pose 2 par construction.
        int markers = body.split("cache_control", -1).length - 1;
        assertThat(markers).isLessThanOrEqualTo(4).isEqualTo(2);
    }

    @Test
    void marksNothingWhenThereIsNoSystemPrompt() {
        build(null);
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                 "usage": {"input_tokens": 1, "output_tokens": 1}}
                """, MediaType.APPLICATION_JSON));

        AgentTurn turn = provider.nextTurn(new AgentTurnRequest("claude-model", "",
                List.of(AgentMessage.userText("bonjour")), List.of(), null));

        assertThat(turn.text()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void countsCachedTokensAsInputSoTheQuotaStaysComparable() {
        build(null);
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                 "usage": {"input_tokens": 500, "cache_creation_input_tokens": 2000,
                           "cache_read_input_tokens": 30000, "output_tokens": 40}}
                """, MediaType.APPLICATION_JSON));

        AgentTurn turn = call();

        // Le quota mesure ce qui a été TRAITÉ, pas ce que le fournisseur nous facture : ne compter
        // que `input_tokens` ferait chuter le décompte de ~98 % ici, en silence.
        assertThat(turn.inputTokens()).isEqualTo(32_500);
        assertThat(turn.outputTokens()).isEqualTo(40);
    }

    @Test
    void keepsCountingExactlyAsBeforeWhenTheResponseHasNoCacheFields() {
        build(null);
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                 "usage": {"input_tokens": 1200, "output_tokens": 40}}
                """, MediaType.APPLICATION_JSON));

        AgentTurn turn = call();

        assertThat(turn.inputTokens()).isEqualTo(1200);
    }

    @Test
    void countsZeroWhenTheResponseCarriesNoUsage() {
        build(null);
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn"}
                """, MediaType.APPLICATION_JSON));

        AgentTurn turn = call();

        assertThat(turn.inputTokens()).isZero();
        assertThat(turn.outputTokens()).isZero();
    }

    // ------------------------------------------------- SF-39-10 : raisonnement adaptatif et effort

    /** Capture le corps d'un tour dont le raisonnement est réglé, sur l'historique fourni. */
    private JsonNode captureBody(AgentReasoning reasoning, List<AgentMessage> messages) {
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> captured.set(
                        ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess("""
                        {"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                         "usage": {"input_tokens": 1, "output_tokens": 1}}
                        """, MediaType.APPLICATION_JSON));
        provider.nextTurn(new AgentTurnRequest("claude-model", "consigne", messages, List.of(), null, reasoning));
        server.verify();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(captured.get());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void asksForAdaptiveThinkingAndTheConfiguredEffort() {
        build(null);
        JsonNode body = captureBody(new AgentReasoning(true, "xhigh"),
                List.of(AgentMessage.userText("bonjour")));

        // Écrit explicitement (D-L5-2) : sur un modèle plus ancien, l'omission voudrait dire
        // « aucun raisonnement ». L'effort vit dans `output_config`, pas à la racine.
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("adaptive");
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("xhigh");
    }

    @Test
    void sendsNothingAboutReasoningWhenItIsNotAsked() {
        build(null);
        JsonNode body = captureBody(AgentReasoning.none(), List.of(AgentMessage.userText("bonjour")));

        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("output_config")).isFalse();
    }

    @Test
    void omitsTheEffortWhenNoneIsConfigured() {
        build(null);
        JsonNode body = captureBody(new AgentReasoning(true, "  "),
                List.of(AgentMessage.userText("bonjour")));

        assertThat(body.path("thinking").path("type").asText()).isEqualTo("adaptive");
        assertThat(body.has("output_config")).isFalse();
    }

    @Test
    void rendersReasoningBlocksWithoutMixingThemIntoTheAnswer() {
        build(null);
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"content": [{"type": "thinking", "thinking": "je regarde le fichier", "signature": "sig-1"},
                             {"type": "redacted_thinking", "data": "chiffre"},
                             {"type": "text", "text": "Voila."}],
                 "stop_reason": "end_turn", "usage": {"input_tokens": 1, "output_tokens": 1}}
                """, MediaType.APPLICATION_JSON));

        AgentTurn turn = call();

        // Le raisonnement n'est pas la réponse : `text()` ne doit porter que la réponse.
        assertThat(turn.text()).isEqualTo("Voila.");
        assertThat(turn.reasoning()).containsExactly(
                new AgentContentBlock.Reasoning("je regarde le fichier", "sig-1"),
                new AgentContentBlock.RedactedReasoning("chiffre"));
        server.verify();
    }

    @Test
    void rendersAReasoningBlockWithoutSignatureAsUnsigned() {
        build(null);
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"content": [{"type": "thinking", "thinking": ""}, {"type": "text", "text": "ok"}],
                 "stop_reason": "end_turn", "usage": {"input_tokens": 1, "output_tokens": 1}}
                """, MediaType.APPLICATION_JSON));

        AgentTurn turn = call();

        assertThat(turn.reasoning()).containsExactly(new AgentContentBlock.Reasoning("", null));
    }

    @Test
    void replaysReasoningBlocksUnchangedAndAheadOfTheRest() {
        build(null);
        JsonNode body = captureBody(new AgentReasoning(true, "high"), List.of(
                AgentMessage.userText("bonjour"),
                AgentMessage.assistant(List.of(
                        new AgentContentBlock.Reasoning("je regarde", "sig-1"),
                        new AgentContentBlock.RedactedReasoning("chiffre"),
                        new AgentContentBlock.Text("Je lis le fichier."))),
                AgentMessage.userText("continue")));

        JsonNode assistant = body.get("messages").get(1).get("content");
        assertThat(assistant.get(0).path("type").asText()).isEqualTo("thinking");
        assertThat(assistant.get(0).path("thinking").asText()).isEqualTo("je regarde");
        assertThat(assistant.get(0).path("signature").asText()).isEqualTo("sig-1");
        assertThat(assistant.get(1).path("type").asText()).isEqualTo("redacted_thinking");
        assertThat(assistant.get(1).path("data").asText()).isEqualTo("chiffre");
        assertThat(assistant.get(2).path("type").asText()).isEqualTo("text");
        // Aucun marqueur de cache sur un bloc de raisonnement, et le plafond du fournisseur (4) tient.
        assertThat(assistant.get(0).has("cache_control")).isFalse();
        assertThat(body.toString().split("cache_control", -1).length - 1).isLessThanOrEqualTo(4);
    }

    @Test
    void omitsAnAbsentSignatureInsteadOfSendingNull() {
        build(null);
        JsonNode body = captureBody(new AgentReasoning(true, "high"), List.of(
                AgentMessage.userText("bonjour"),
                AgentMessage.assistant(List.of(new AgentContentBlock.Reasoning("", null))),
                AgentMessage.userText("continue")));

        JsonNode block = body.get("messages").get(1).get("content").get(0);
        assertThat(block.has("signature")).isFalse();
        assertThat(block.path("thinking").asText()).isEmpty();
    }
}
