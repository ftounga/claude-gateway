package fr.claudegateway.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.ai.AIProviderException;
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
    /** Attentes réellement demandées par la boucle de réessai (SF-39-11), en millisecondes. */
    private final List<Long> waits = new ArrayList<>();

    private void build(Integer agentMaxTokens) {
        build(agentMaxTokens, 3);
    }

    private void build(Integer agentMaxTokens, Integer agentMaxAttempts) {
        AnthropicProperties properties = new AnthropicProperties(
                "sk-ant-test-key", "https://api.anthropic.com", "2023-06-01",
                null, null, 4096, agentMaxTokens, Duration.ofSeconds(5), Duration.ofSeconds(5),
                agentMaxAttempts);
        RestClient.Builder builder = RestClient.builder();
        // La fabrique du serveur simulé doit rester en place : d'où le `null` (SF-39-11).
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new AnthropicAgentProvider(properties, builder, null, waits::add);
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
        // TTL 1 h (F-130 / SF-130-01) : un usage étalé relit le préfixe au lieu de le ré-écrire.
        assertThat(system.get(0).path("cache_control").path("ttl").asText()).isEqualTo("1h");
    }

    @Test
    void marksTheLastBlockOfTheLastMessageForCaching() {
        build(null);
        JsonNode body = captureBody();

        JsonNode messages = body.get("messages");
        JsonNode lastBlock = messages.get(messages.size() - 1).get("content").get(0);
        assertThat(lastBlock.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        // TTL 1 h (F-130 / SF-130-01) : les deux marqueurs portent le même TTL (aucun TTL mixte).
        assertThat(lastBlock.path("cache_control").path("ttl").asText()).isEqualTo("1h");
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
        // …et le cache voyage AUSSI séparément (F-63 / SF-63-02), pour que le décompte le facture à
        // son prix — un dixième du tarif d'entrée en lecture — au lieu du plein tarif.
        assertThat(turn.cacheReadTokens()).isEqualTo(30_000);
        assertThat(turn.cacheWriteTokens()).isEqualTo(2_000);
        assertThat(turn.fullPriceInputTokens()).isEqualTo(500);
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

    // --- Édition de contexte (F-39 / SF-39-12) ---------------------------------------------------

    /** Corps et en-têtes d'un tour porteur d'une politique de contexte donnée. */
    private JsonNode captureBodyWithContext(AgentContextPolicy policy, List<String> betaHeaders) {
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    captured.set(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                            .getBodyAsString());
                    List<String> beta = request.getHeaders().get("anthropic-beta");
                    if (beta != null) {
                        betaHeaders.addAll(beta);
                    }
                })
                .andRespond(withSuccess("""
                        {"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                         "usage": {"input_tokens": 1, "output_tokens": 1},
                         "context_management": {"applied_edits": [
                            {"type": "clear_tool_uses_20250919", "cleared_tool_uses": 8,
                             "cleared_input_tokens": 50000}]}}
                        """, MediaType.APPLICATION_JSON));
        AgentTurn turn = provider.nextTurn(new AgentTurnRequest("claude-model", "consigne",
                List.of(AgentMessage.userText("bonjour")), List.of(), null, null, policy));
        // Une réponse portant des éditions appliquées se traduit normalement : rien n'est altéré.
        assertThat(turn.text()).isEqualTo("ok");
        assertThat(turn.inputTokens()).isEqualTo(1);
        server.verify();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(captured.get());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void asksTheProviderToClearStaleToolResults() {
        build(null);
        List<String> betaHeaders = new ArrayList<>();
        JsonNode body = captureBodyWithContext(
                new AgentContextPolicy(true, 200_000, 3, 20_000), betaHeaders);

        JsonNode edit = body.path("context_management").path("edits").get(0);
        assertThat(edit.path("type").asText()).isEqualTo("clear_tool_uses_20250919");
        assertThat(edit.path("trigger").path("type").asText()).isEqualTo("input_tokens");
        assertThat(edit.path("trigger").path("value").asInt()).isEqualTo(200_000);
        assertThat(edit.path("keep").path("type").asText()).isEqualTo("tool_uses");
        assertThat(edit.path("keep").path("value").asInt()).isEqualTo(3);
        assertThat(edit.path("clear_at_least").path("type").asText()).isEqualTo("input_tokens");
        assertThat(edit.path("clear_at_least").path("value").asInt()).isEqualTo(20_000);
        // Les paramètres d'appel restent : c'est la SORTIE d'une commande qui pèse (D-L6-8).
        assertThat(edit.has("clear_tool_inputs")).isFalse();
        assertThat(betaHeaders).contains("context-management-2025-06-27");
    }

    @Test
    void sendsNoContextManagementWhenThePolicyIsInactive() {
        build(null);
        List<String> betaHeaders = new ArrayList<>();
        JsonNode body = captureBodyWithContext(AgentContextPolicy.none(), betaHeaders);

        // Ni le champ ni l'en-tête beta : le corps est exactement celui d'avant SF-39-12.
        assertThat(body.has("context_management")).isFalse();
        assertThat(betaHeaders).isEmpty();
    }

    // --- Tenue longue : délai HTTP et réessai des refus temporaires (F-39 / SF-39-11) ------------

    /** Réponse d'échec, avec ou sans en-tête {@code Retry-After}. */
    private void respondWithStatus(int status, String retryAfterSeconds) {
        var response = withStatus(HttpStatusCode.valueOf(status));
        if (retryAfterSeconds != null) {
            response = response.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
        }
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(response);
    }

    private void respondWithSuccess() {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                         "usage": {"input_tokens": 1, "output_tokens": 1}}
                        """, MediaType.APPLICATION_JSON));
    }

    @Test
    void retriesAfterATooManyRequestsRefusal() {
        build(null);
        respondWithStatus(429, null);
        respondWithSuccess();

        AgentTurn turn = call();

        assertThat(turn.text()).isEqualTo("ok");
        // Deux appels : le refus temporaire n'a pas tué le tour.
        server.verify();
        assertThat(waits).hasSize(1);
    }

    @Test
    void retriesAfterAnOverloadedRefusal() {
        build(null);
        respondWithStatus(529, null);
        respondWithSuccess();

        assertThat(call().text()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void givesUpOnceTheAttemptsAreExhausted() {
        build(null, 3);
        respondWithStatus(429, null);
        respondWithStatus(429, null);
        respondWithStatus(429, null);

        assertThatThrownBy(this::call).isInstanceOf(AIProviderException.class);

        // Exactement trois appels : la borne de tentatives est celle de la configuration.
        server.verify();
        assertThat(waits).hasSize(2);
    }

    @Test
    void neverRetriesAPermanentRefusal() {
        build(null);
        respondWithStatus(400, null);

        assertThatThrownBy(this::call).isInstanceOf(AIProviderException.class);

        // Un seul appel : rejouer un 400 ne ferait que le reproduire.
        server.verify();
        assertThat(waits).isEmpty();
    }

    @Test
    void translatesPromptTooLongIntoANeutralException() {
        build(null);
        // Corps réellement renvoyé par le fournisseur dans ce cas (F-117 / SF-117-02).
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatusCode.valueOf(400)).body("""
                        {"type":"error","error":{"type":"invalid_request_error",
                         "message":"prompt is too long: 250000 tokens > 200000 maximum"}}""")
                        .contentType(MediaType.APPLICATION_JSON));

        // Signal NEUTRE (pas une AIProviderException) : la boucle doit pouvoir compacter puis relancer.
        assertThatThrownBy(this::call).isInstanceOf(AgentPromptTooLongException.class);

        // Un seul appel : rejouer tel quel redonnerait le même 400 — il faut d'abord réduire.
        server.verify();
        assertThat(waits).isEmpty();
    }

    @Test
    void keepsOtherBadRequestsAsProviderFailure() {
        build(null);
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatusCode.valueOf(400)).body("""
                        {"type":"error","error":{"type":"invalid_request_error",
                         "message":"messages: unexpected role"}}""")
                        .contentType(MediaType.APPLICATION_JSON));

        // Un autre 400 reste un échec fournisseur, pas un débordement de contexte.
        assertThatThrownBy(this::call).isInstanceOf(AIProviderException.class);
        server.verify();
    }

    @Test
    void neverRetriesAServerErrorBecauseTheCallMayHaveBeenProcessed() {
        build(null);
        respondWithStatus(500, null);

        assertThatThrownBy(this::call).isInstanceOf(AIProviderException.class);

        server.verify();
        assertThat(waits).isEmpty();
    }

    @Test
    void honoursTheRetryAfterHeader() {
        build(null);
        respondWithStatus(429, "2");
        respondWithSuccess();

        call();

        assertThat(waits).containsExactly(2_000L);
    }

    @Test
    void capsAnOutlandishRetryAfterHeader() {
        build(null);
        respondWithStatus(429, "999");
        respondWithSuccess();

        call();

        assertThat(waits).containsExactly(AgentRetryPolicy.MAX_DELAY_MS);
    }

    @Test
    void fallsBackToBackoffWhenTheRetryAfterHeaderIsNotReadable() {
        build(null);
        // Forme « date HTTP » : non interprétée, car elle exigerait une horloge commune.
        respondWithStatus(429, "Wed, 21 Oct 2026 07:28:00 GMT");
        respondWithSuccess();

        call();

        assertThat(waits).hasSize(1);
        assertThat(waits.get(0))
                .isBetween(AgentRetryPolicy.INITIAL_DELAY_MS / 2, AgentRetryPolicy.INITIAL_DELAY_MS);
    }

    @Test
    void replaysTheExactSameBodyOnRetry() {
        build(null);
        List<String> bodies = new ArrayList<>();
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andExpect(request -> bodies.add(
                        ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withStatus(HttpStatusCode.valueOf(429)));
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andExpect(request -> bodies.add(
                        ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess("""
                        {"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                         "usage": {"input_tokens": 1, "output_tokens": 1}}
                        """, MediaType.APPLICATION_JSON));

        provider.nextTurn(new AgentTurnRequest("claude-model", "consigne",
                List.of(AgentMessage.userText("bonjour")), List.of(), null,
                new AgentReasoning(true, "high")));

        // Marqueurs de cache et blocs signés identiques : un réessai rejoue, il ne reconstruit pas.
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
    }

    // ------------------------------------------------- SF-39-20 : la recherche web, relayée

    @Test
    void declaresTheProviderSideWebToolsAlongsideOurs() {
        build(null);
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.tools[?(@.name == 'web_search')].type")
                        .value("web_search_20260209"))
                .andExpect(jsonPath("$.tools[?(@.name == 'web_fetch')].type")
                        .value("web_fetch_20260209"))
                // Les nôtres restent déclarés : les outils serveur s'ajoutent, ils ne remplacent pas.
                .andExpect(jsonPath("$.tools[?(@.name == 'read_file')]").exists())
                .andRespond(withSuccess("""
                        {"content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                         "usage": {"input_tokens": 1, "output_tokens": 1}}
                        """, MediaType.APPLICATION_JSON));

        provider.nextTurn(new AgentTurnRequest("claude-model", "consigne",
                List.of(AgentMessage.userText("cherche")),
                List.of(new AgentTool("read_file", "Lit un fichier", java.util.Map.of("type", "object"))),
                null));

        server.verify();
    }

    @Test
    void aWebSearchResultIsNotMistakenForAToolWeMustExecute() {
        build(null);
        // Les outils serveur s'exécutent CHEZ le fournisseur : leurs blocs de résultat arrivent dans
        // la même réponse, et la gateway n'a rien à exécuter.
        respondWith("end_turn", """
                [{"type": "server_tool_use", "id": "srv_1", "name": "web_search", "input": {}},
                 {"type": "web_search_tool_result", "tool_use_id": "srv_1", "content": []},
                 {"type": "text", "text": "D'après le web…"}]""");

        AgentTurn turn = call();

        assertThat(turn.toolCalls()).isEmpty();
        assertThat(turn.text()).isEqualTo("D'après le web…");
        assertThat(turn.finished()).isTrue();
    }

    // ------------------------------------------- F-116 / SF-116-01 : l'appel modèle en flux

    /**
     * Flux SSE représentatif : raisonnement signé, texte en deux deltas, appel d'outil dont le JSON
     * d'entrée arrive en deux fragments, puis usage final et {@code stop_reason: tool_use}.
     */
    private static final String SSE_TOOL_USE = String.join("\n",
            "event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,"
                    + "\"cache_creation_input_tokens\":2000,\"cache_read_input_tokens\":30000,"
                    + "\"output_tokens\":5}}}",
            "",
            "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                    + "{\"type\":\"thinking\",\"thinking\":\"\"}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                    + "{\"type\":\"thinking_delta\",\"thinking\":\"je regarde\"}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                    + "{\"type\":\"signature_delta\",\"signature\":\"sig-1\"}}",
            "",
            "event: content_block_stop",
            "data: {\"type\":\"content_block_stop\",\"index\":0}",
            "",
            "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":"
                    + "{\"type\":\"text\",\"text\":\"\"}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":"
                    + "{\"type\":\"text_delta\",\"text\":\"Je \"}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":"
                    + "{\"type\":\"text_delta\",\"text\":\"lis.\"}}",
            "",
            "event: content_block_stop",
            "data: {\"type\":\"content_block_stop\",\"index\":1}",
            "",
            "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":"
                    + "{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"read_file\",\"input\":{}}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":"
                    + "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\"}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":"
                    + "{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"a.txt\\\"}\"}}",
            "",
            "event: content_block_stop",
            "data: {\"type\":\"content_block_stop\",\"index\":2}",
            "",
            "event: message_delta",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                    + "\"usage\":{\"output_tokens\":20}}",
            "",
            "event: message_stop",
            "data: {\"type\":\"message_stop\"}",
            "", "");

    /** Réponse non streamée équivalente au flux {@link #SSE_TOOL_USE}. */
    private static final String JSON_TOOL_USE = """
            {"content": [
               {"type": "thinking", "thinking": "je regarde", "signature": "sig-1"},
               {"type": "text", "text": "Je lis."},
               {"type": "tool_use", "id": "tu_1", "name": "read_file", "input": {"path": "a.txt"}}],
             "stop_reason": "tool_use",
             "usage": {"input_tokens": 10, "cache_creation_input_tokens": 2000,
                       "cache_read_input_tokens": 30000, "output_tokens": 20}}
            """;

    private void respondWithSse(String sse) {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(sse, MediaType.TEXT_EVENT_STREAM));
    }

    private void respondWithJson(String json) {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private AgentTurn callStreamed(List<String> deltas) {
        return provider.nextTurn(
                new AgentTurnRequest("claude-model", "consigne",
                        List.of(AgentMessage.userText("bonjour")),
                        List.of(new AgentTool("read_file", "Lit un fichier",
                                java.util.Map.of("type", "object"))),
                        null),
                delta -> deltas.add(delta));
    }

    @Test
    void theStreamedTurnIsStrictlyEquivalentToTheNonStreamedOne() {
        // L'invariant central de F-116 : le streaming ne change QUE le moment d'affichage. Le tour
        // reconstitué depuis le flux doit être égal, champ pour champ, à celui de l'appel complet.
        build(null);
        respondWithSse(SSE_TOOL_USE);
        AgentTurn streamed = callStreamed(new ArrayList<>());

        build(null);
        respondWithJson(JSON_TOOL_USE);
        AgentTurn nonStreamed = provider.nextTurn(new AgentTurnRequest("claude-model", "consigne",
                List.of(AgentMessage.userText("bonjour")),
                List.of(new AgentTool("read_file", "Lit un fichier", java.util.Map.of("type", "object"))),
                null));

        assertThat(streamed).isEqualTo(nonStreamed);
        // …et, explicitement, chacun des champs qui font l'équivalence.
        assertThat(streamed.text()).isEqualTo("Je lis.");
        assertThat(streamed.finished()).isFalse();
        assertThat(streamed.truncated()).isFalse();
        assertThat(streamed.toolCalls()).hasSize(1);
        assertThat(streamed.toolCalls().get(0).name()).isEqualTo("read_file");
        assertThat(streamed.toolCalls().get(0).input().path("path").asText()).isEqualTo("a.txt");
        assertThat(streamed.reasoning()).containsExactly(
                new AgentContentBlock.Reasoning("je regarde", "sig-1"));
        assertThat(streamed.inputTokens()).isEqualTo(32_010);
        assertThat(streamed.outputTokens()).isEqualTo(20);
        assertThat(streamed.cacheReadTokens()).isEqualTo(30_000);
        assertThat(streamed.cacheWriteTokens()).isEqualTo(2_000);
    }

    @Test
    void pushesTextDeltasInOrderAndNeverTheReasoning() {
        build(null);
        respondWithSse(SSE_TOOL_USE);
        List<String> deltas = new ArrayList<>();

        callStreamed(deltas);

        // Le texte défile fragment par fragment, dans l'ordre ; le raisonnement n'y figure jamais.
        assertThat(deltas).containsExactly("Je ", "lis.");
    }

    @Test
    void reconstructsUsageFromStartAndTheFinalOutputFromMessageDelta() {
        build(null);
        respondWithSse(SSE_TOOL_USE);

        AgentTurn turn = callStreamed(new ArrayList<>());

        // L'entrée et le cache viennent de message_start ; la sortie FINALE de message_delta (20),
        // pas la valeur initiale de message_start (5).
        assertThat(turn.outputTokens()).isEqualTo(20);
        assertThat(turn.inputTokens()).isEqualTo(32_010);
    }

    @Test
    void marksAStreamedTurnTruncatedWhenItHitsTheOutputCap() {
        build(null);
        respondWithSse(String.join("\n",
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":"
                        + "{\"input_tokens\":1,\"output_tokens\":1}}}",
                "",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                        + "{\"type\":\"text\",\"text\":\"\"}}",
                "",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                        + "{\"type\":\"text_delta\",\"text\":\"Je vais créer\"}}",
                "",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},"
                        + "\"usage\":{\"output_tokens\":8}}",
                "",
                "data: {\"type\":\"message_stop\"}",
                "", ""));

        AgentTurn turn = callStreamed(new ArrayList<>());

        assertThat(turn.truncated()).isTrue();
        assertThat(turn.finished()).isTrue();
    }

    @Test
    void sendsTheSameBodyAsTheNonStreamedPathPlusStreamTrue() {
        build(null);
        java.util.concurrent.atomic.AtomicReference<String> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> captured.set(
                        ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                .getBodyAsString()))
                .andRespond(withSuccess(SSE_TOOL_USE, MediaType.TEXT_EVENT_STREAM));

        callStreamed(new ArrayList<>());

        try {
            JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(captured.get());
            // Le flux est demandé…
            assertThat(body.path("stream").asBoolean()).isTrue();
            // …et le préfixe caché est intact : système en liste de blocs marquée, dernier bloc du
            // dernier message marqué. Le corps est celui du non streamé + `stream:true`.
            assertThat(body.path("system").get(0).path("cache_control").path("type").asText())
                    .isEqualTo("ephemeral");
            // TTL 1 h porté aussi sur le chemin streamé (F-130 / SF-130-01) : le corps est celui du
            // non streamé + `stream:true`, le marqueur ne bouge pas.
            assertThat(body.path("system").get(0).path("cache_control").path("ttl").asText())
                    .isEqualTo("1h");
            JsonNode messages = body.get("messages");
            assertThat(messages.get(messages.size() - 1).get("content").get(0)
                    .path("cache_control").path("type").asText()).isEqualTo("ephemeral");
            assertThat(messages.get(messages.size() - 1).get("content").get(0)
                    .path("cache_control").path("ttl").asText()).isEqualTo("1h");
            // Deux marqueurs, comme le non streamé (SF-39-01) : le flux n'en ajoute aucun.
            assertThat(captured.get().split("cache_control", -1).length - 1).isEqualTo(2);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void retriesTheStreamedCallAfterATemporaryRefusalWithTheSameBody() {
        build(null);
        List<String> bodies = new ArrayList<>();
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andExpect(request -> bodies.add(
                        ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                .getBodyAsString()))
                .andRespond(withStatus(HttpStatusCode.valueOf(429)));
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andExpect(request -> bodies.add(
                        ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                .getBodyAsString()))
                .andRespond(withSuccess(SSE_TOOL_USE, MediaType.TEXT_EVENT_STREAM));

        AgentTurn turn = callStreamed(new ArrayList<>());

        // Le refus temporaire est rejoué exactement comme le non streamé, corps identique.
        assertThat(turn.text()).isEqualTo("Je lis.");
        assertThat(waits).hasSize(1);
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
        server.verify();
    }

    @Test
    void fallsBackToTheFullCallWhenTheProviderRefusesTheStream() {
        build(null);
        // Refus permanent du flux (400) : au lieu de tuer le tour, on retombe sur l'appel complet.
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withStatus(HttpStatusCode.valueOf(400)));
        respondWithJson(JSON_TOOL_USE);

        AgentTurn turn = callStreamed(new ArrayList<>());

        assertThat(turn.text()).isEqualTo("Je lis.");
        assertThat(turn.toolCalls()).hasSize(1);
        server.verify();
    }

    @Test
    void fallsBackToTheFullCallWhenTheStreamIsCutBeforeTheEnd() {
        build(null);
        // Flux coupé : la ligne `message_stop` n'arrive jamais.
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess(String.join("\n",
                        "data: {\"type\":\"message_start\",\"message\":{\"usage\":"
                                + "{\"input_tokens\":1,\"output_tokens\":1}}}",
                        "",
                        "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                                + "{\"type\":\"text\",\"text\":\"\"}}",
                        "",
                        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                                + "{\"type\":\"text_delta\",\"text\":\"Je li\"}}",
                        "", ""), MediaType.TEXT_EVENT_STREAM));
        respondWithJson(JSON_TOOL_USE);

        AgentTurn turn = callStreamed(new ArrayList<>());

        // Repli propre sur l'appel complet : le tour n'est pas perdu.
        assertThat(turn.text()).isEqualTo("Je lis.");
        server.verify();
    }

    @Test
    void doesNotFallBackWhenTemporaryRefusalsAreExhausted() {
        build(null, 3);
        // 429 sur les trois tentatives streamées : c'est une surcharge, pas un refus du flux — on ne
        // gaspille pas un appel complet de repli qui échouerait de la même manière.
        respondWithStatus(429, null);
        respondWithStatus(429, null);
        respondWithStatus(429, null);

        assertThatThrownBy(() -> callStreamed(new ArrayList<>()))
                .isInstanceOf(AIProviderException.class);
        server.verify();
        assertThat(waits).hasSize(2);
    }
}
