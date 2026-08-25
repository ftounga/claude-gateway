package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import fr.claudegateway.ai.AnthropicProperties;

/**
 * Vérifie {@link AnthropicManagedAgentProvider} contre un serveur simulé ({@link MockRestServiceServer}) :
 * endpoints, en-têtes (dont {@code anthropic-beta}), parsing des identifiants, agrégation du polling,
 * et traduction des erreurs en {@link AgentProviderException}. Aucune session live ; {@code pollDelay=0}.
 */
class AnthropicManagedAgentProviderTest {

    private static final String BETA = "managed-agents-2026-04-01";
    private static final String FILES_BETA = "files-api-2025-04-14";

    private MockRestServiceServer server;
    private AnthropicManagedAgentProvider provider;

    @BeforeEach
    void setUp() {
        AnthropicProperties properties = new AnthropicProperties(
                "sk-ant-test-key", "https://api.anthropic.com", "2023-06-01",
                null, null, null, Duration.ofSeconds(5));
        // pollDelay = 0 : polling déterministe sans Thread.sleep réel.
        AtelierAgentProperties agentProperties = new AtelierAgentProperties(
                false, null, null, null, null, null, null, null, Duration.ZERO, null, null, null);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        provider = new AnthropicManagedAgentProvider(properties, agentProperties, builder);
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
    void uploadFilePostsMultipartWithFilesBetaAndParsesFileId() {
        server.expect(requestTo("https://api.anthropic.com/v1/files"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("anthropic-beta", FILES_BETA))
                .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith("multipart/form-data")))
                .andRespond(withSuccess("{\"id\":\"file_abc\"}", MediaType.APPLICATION_JSON));

        String fileId = provider.uploadFile("src/App.java", "class App {}".getBytes());

        assertThat(fileId).isEqualTo("file_abc");
        server.verify();
    }

    @Test
    void createSessionPostsResourcesInBodyAndParsesId() {
        server.expect(requestTo("https://api.anthropic.com/v1/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("anthropic-beta", BETA))
                .andExpect(jsonPath("$.agent").value("agent_456"))
                .andExpect(jsonPath("$.environment_id").value("env_123"))
                .andExpect(jsonPath("$.resources[0].type").value("file"))
                .andExpect(jsonPath("$.resources[0].file_id").value("file_abc"))
                .andExpect(jsonPath("$.resources[0].mount_path").value("/workspace/src/App.java"))
                .andRespond(withSuccess("{\"id\":\"sess_1\"}", MediaType.APPLICATION_JSON));

        ManagedSession session = provider.createSession("agent_456", "env_123",
                List.of(new FileMount("file_abc", "/workspace/src/App.java")));

        assertThat(session.id()).isEqualTo("sess_1");
        server.verify();
    }

    @Test
    void createSessionWithASystemOverrideUsesTheAgentWithOverridesForm() {
        // F-34 / SF-34-01 : instructions du projet portées par une surcharge SESSION-LOCALE ;
        // l'agent provisionné pour la plateforme n'est jamais modifié.
        server.expect(requestTo("https://api.anthropic.com/v1/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.agent.type").value("agent_with_overrides"))
                .andExpect(jsonPath("$.agent.id").value("agent_456"))
                .andExpect(jsonPath("$.agent.system").value("prompt plateforme + projet"))
                .andRespond(withSuccess("{\"id\":\"sess_2\"}", MediaType.APPLICATION_JSON));

        ManagedSession session = provider.createSession(
                "agent_456", "env_123", List.of(), null, "prompt plateforme + projet");

        assertThat(session.id()).isEqualTo("sess_2");
        server.verify();
    }

    @Test
    void createSessionWithoutOverrideKeepsThePlainAgentIdentifier() {
        // Aucune instruction de projet : le corps envoyé reste celui d'avant F-34 (aucune régression).
        server.expect(requestTo("https://api.anthropic.com/v1/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.agent").value("agent_456"))
                .andRespond(withSuccess("{\"id\":\"sess_3\"}", MediaType.APPLICATION_JSON));

        provider.createSession("agent_456", "env_123", List.of(), null, null);

        server.verify();
    }

    @Test
    void createSessionWithAskPolicyOverridesToolsWithAlwaysAskOnBash() {
        // F-33 / SF-33-01 : la surcharge d'outils REMPLACE en bloc celle de l'agent — on renvoie donc
        // le toolset complet (default_config en always_allow) avec le seul `bash` en always_ask.
        server.expect(requestTo("https://api.anthropic.com/v1/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.agent.type").value("agent_with_overrides"))
                .andExpect(jsonPath("$.agent.id").value("agent_456"))
                .andExpect(jsonPath("$.agent.system").doesNotExist())
                .andExpect(jsonPath("$.agent.tools[0].type").value("agent_toolset_20260401"))
                .andExpect(jsonPath("$.agent.tools[0].default_config.enabled").value(true))
                .andExpect(jsonPath("$.agent.tools[0].default_config.permission_policy.type")
                        .value("always_allow"))
                .andExpect(jsonPath("$.agent.tools[0].configs[0].name").value("bash"))
                .andExpect(jsonPath("$.agent.tools[0].configs[0].permission_policy.type")
                        .value("always_ask"))
                .andRespond(withSuccess("{\"id\":\"sess_4\"}", MediaType.APPLICATION_JSON));

        ManagedSession session = provider.createSession("agent_456", "env_123", List.of(), null, null,
                SessionPermissions.ASK_BEFORE_SHELL);

        assertThat(session.id()).isEqualTo("sess_4");
        server.verify();
    }

    @Test
    void createSessionWithAskPolicyAndProjectInstructionsCarriesBothOverrides() {
        // Les deux surcharges (F-34 système, F-33 outils) vivent dans le MÊME agent_with_overrides.
        server.expect(requestTo("https://api.anthropic.com/v1/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.agent.type").value("agent_with_overrides"))
                .andExpect(jsonPath("$.agent.system").value("prompt plateforme + projet"))
                .andExpect(jsonPath("$.agent.tools[0].configs[0].name").value("bash"))
                .andRespond(withSuccess("{\"id\":\"sess_5\"}", MediaType.APPLICATION_JSON));

        provider.createSession("agent_456", "env_123", List.of(), null, "prompt plateforme + projet",
                SessionPermissions.ASK_BEFORE_SHELL);

        server.verify();
    }

    @Test
    void createSessionWithAllowAllPolicyKeepsThePlainAgentIdentifier() {
        // Option non activée : corps strictement identique à celui d'avant F-33 (aucune régression).
        server.expect(requestTo("https://api.anthropic.com/v1/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.agent").value("agent_456"))
                .andRespond(withSuccess("{\"id\":\"sess_6\"}", MediaType.APPLICATION_JSON));

        provider.createSession("agent_456", "env_123", List.of(), null, null,
                SessionPermissions.ALLOW_ALL);

        server.verify();
    }

    @Test
    void sendUserMessagePostsUserMessageEvent() {
        server.expect(requestTo("https://api.anthropic.com/v1/sessions/sess_1/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("anthropic-beta", BETA))
                .andExpect(jsonPath("$.events[0].type").value("user.message"))
                .andExpect(jsonPath("$.events[0].content[0].type").value("text"))
                .andExpect(jsonPath("$.events[0].content[0].text").value("Corrige le bug."))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        provider.sendUserMessage("sess_1", "Corrige le bug.");

        server.verify();
    }

    @Test
    void awaitCompletionPollsUntilIdleAndAggregatesAgentMessages() {
        server.expect(requestToUriTemplate(
                "https://api.anthropic.com/v1/sessions/sess_1/events?limit=1000"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("anthropic-beta", BETA))
                .andRespond(withSuccess(
                        "{\"data\":[{\"type\":\"session.status_running\",\"id\":\"e0\"}],\"next_page\":\"c1\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestToUriTemplate(
                "https://api.anthropic.com/v1/sessions/sess_1/events?limit=1000&page=c1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"data\":[{\"type\":\"agent.message\",\"id\":\"e1\",\"content\":[{\"type\":\"text\",\"text\":\"Bonjour \"},"
                                + "{\"type\":\"text\",\"text\":\"monde\"}]},"
                                + "{\"type\":\"session.status_idle\",\"id\":\"e2\",\"stop_reason\":{\"type\":\"end_turn\"}}]}",
                        MediaType.APPLICATION_JSON));

        SessionRun run = provider.awaitCompletion("sess_1", Duration.ofSeconds(30), 10);

        assertThat(run.reply()).isEqualTo("Bonjour monde");
        assertThat(run.stopReason()).isEqualTo("end_turn");
        server.verify();
    }

    @Test
    void awaitCompletionWithListenerNotifiesTextAndStatusAndAggregatesSameReply() {
        server.expect(requestToUriTemplate(
                "https://api.anthropic.com/v1/sessions/sess_1/events?limit=1000"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"type\":\"session.status_running\",\"id\":\"e0\"},"
                                + "{\"type\":\"agent.tool_use\",\"id\":\"e1\",\"name\":\"bash\",\"input\":{\"command\":\"ls -la\"}}],"
                                + "\"next_page\":\"c1\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestToUriTemplate(
                "https://api.anthropic.com/v1/sessions/sess_1/events?limit=1000&page=c1"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"type\":\"agent.message\",\"id\":\"e2\",\"content\":[{\"type\":\"text\",\"text\":\"Bonjour \"},"
                                + "{\"type\":\"text\",\"text\":\"monde\"}]},"
                                + "{\"type\":\"session.status_idle\",\"id\":\"e3\",\"stop_reason\":\"end_turn\"}]}",
                        MediaType.APPLICATION_JSON));

        RecordingListener listener = new RecordingListener();
        SessionRun run = provider.awaitCompletion("sess_1", Duration.ofSeconds(30), 10, listener);

        // Réponse agrégée identique à la variante 3-args.
        assertThat(run.reply()).isEqualTo("Bonjour monde");
        assertThat(run.stopReason()).isEqualTo("end_turn");
        // Le listener a reçu le texte de l'agent, l'action (outil + commande) et les transitions d'état.
        assertThat(listener.texts).containsExactly("Bonjour monde");
        assertThat(listener.actions).containsExactly("bash:ls -la");
        assertThat(listener.states).containsExactly("running", "idle");
        server.verify();
    }

    @Test
    void awaitCompletionThreeArgsDelegatesWithNoopListenerNoRegression() {
        server.expect(requestToUriTemplate(
                "https://api.anthropic.com/v1/sessions/sess_1/events?limit=1000"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"type\":\"agent.message\",\"id\":\"e0\",\"content\":\"Salut\"},"
                                + "{\"type\":\"session.status_idle\",\"id\":\"e1\",\"stop_reason\":\"end_turn\"}]}",
                        MediaType.APPLICATION_JSON));

        // La variante 3-args (NOOP) agrège la réponse sans lever malgré l'absence de listener.
        SessionRun run = provider.awaitCompletion("sess_1", Duration.ofSeconds(30), 10);

        assertThat(run.reply()).isEqualTo("Salut");
        assertThat(run.stopReason()).isEqualTo("end_turn");
        server.verify();
    }

    @Test
    void awaitCompletionRelaysToolUseIdWithTheCommand() {
        // F-30 SF-30-02 : sans l'identifiant sur la commande, le frontend ne peut apparier la sortie
        // que par ordre d'arrivée. Absent de l'event, il vaut null (repli assumé côté affichage).
        server.expect(requestToUriTemplate(
                "https://api.anthropic.com/v1/sessions/sess_1/events?limit=1000"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"type\":\"agent.tool_use\",\"id\":\"e0\",\"name\":\"bash\","
                                + "\"tool_use_id\":\"tu_1\",\"input\":{\"command\":\"npm test\"}},"
                                + "{\"type\":\"agent.tool_use\",\"id\":\"e1\",\"name\":\"bash\","
                                + "\"input\":{\"command\":\"npm run build\"}},"
                                + "{\"type\":\"session.status_idle\",\"id\":\"e2\",\"stop_reason\":\"end_turn\"}]}",
                        MediaType.APPLICATION_JSON));

        RecordingListener listener = new RecordingListener();
        provider.awaitCompletion("sess_1", Duration.ofSeconds(30), 10, listener);

        assertThat(listener.actions).containsExactly("bash:npm test", "bash:npm run build");
        assertThat(listener.actionIds).containsExactly("tu_1", "null");
        server.verify();
    }

    // ---- F-30 SF-30-08 : diagnostic des erreurs du fournisseur ----

    @Test
    void exhaustedCreditIsRecognisedAsItsOwnFailure() {
        // Le message générique « réessayez » invitait à refaire ce qui ne peut pas aboutir.
        server.expect(requestToUriTemplate("https://api.anthropic.com/v1/sessions"))
                .andRespond(withBadRequest().body(
                        "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                                + "\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.createSession("agent_1", "env_1", List.of()))
                .isInstanceOf(AgentCreditExhaustedException.class);
        server.verify();
    }

    @Test
    void otherBadRequestsStayGenericProviderFailures() {
        server.expect(requestToUriTemplate("https://api.anthropic.com/v1/sessions"))
                .andRespond(withBadRequest().body(
                        "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                                + "\"message\":\"environment_id: field required\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.createSession("agent_1", "env_1", List.of()))
                .isInstanceOf(AgentProviderException.class)
                .isNotInstanceOf(AgentCreditExhaustedException.class);
        server.verify();
    }

    @Test
    void unparseableErrorBodyStillFailsCleanly() {
        // Corps non exploitable : le statut seul oriente, et rien ne doit lever d'exception de parsing.
        server.expect(requestToUriTemplate("https://api.anthropic.com/v1/sessions"))
                .andRespond(withServerError().body("<html>502 Bad Gateway</html>"));

        assertThatThrownBy(() -> provider.createSession("agent_1", "env_1", List.of()))
                .isInstanceOf(AgentProviderException.class)
                .isNotInstanceOf(AgentCreditExhaustedException.class);
        server.verify();
    }

    @Test
    void authenticationFailuresAreNotMistakenForExhaustedCredit() {
        server.expect(requestToUriTemplate("https://api.anthropic.com/v1/sessions"))
                .andRespond(withUnauthorizedRequest().body(
                        "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                                + "\"message\":\"invalid x-api-key\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.createSession("agent_1", "env_1", List.of()))
                .isInstanceOf(AgentProviderException.class)
                .isNotInstanceOf(AgentCreditExhaustedException.class);
        server.verify();
    }

    /** Écouteur de test enregistrant les notifications reçues pour vérification. */
    // ---- F-30 SF-30-01 : relais de la sortie des commandes ----

    @Test
    void awaitCompletionRelaysToolOutputWithCommandAndResult() {
        server.expect(requestToUriTemplate(
                "https://api.anthropic.com/v1/sessions/sess_1/events?limit=1000"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"type\":\"agent.tool_use\",\"id\":\"e0\",\"name\":\"bash\","
                                + "\"tool_use_id\":\"tu_1\",\"input\":{\"command\":\"npm test\"}},"
                                + "{\"type\":\"agent.tool_result\",\"id\":\"e1\",\"name\":\"bash\","
                                + "\"tool_use_id\":\"tu_1\",\"content\":[{\"type\":\"text\",\"text\":\"12 passing\"}]},"
                                + "{\"type\":\"session.status_idle\",\"id\":\"e2\",\"stop_reason\":\"end_turn\"}]}",
                        MediaType.APPLICATION_JSON));

        RecordingListener listener = new RecordingListener();
        provider.awaitCompletion("sess_1", Duration.ofSeconds(30), 10, listener);

        assertThat(listener.actions).containsExactly("bash:npm test");
        assertThat(listener.results).containsExactly("bash|tu_1|12 passing|false");
        server.verify();
    }

    @Test
    void awaitCompletionMarksFailedToolResultAsError() {
        server.expect(requestToUriTemplate(
                "https://api.anthropic.com/v1/sessions/sess_1/events?limit=1000"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"type\":\"agent.tool_result\",\"id\":\"e0\",\"name\":\"bash\","
                                + "\"is_error\":true,\"content\":\"command not found\"},"
                                + "{\"type\":\"session.status_idle\",\"id\":\"e1\",\"stop_reason\":\"end_turn\"}]}",
                        MediaType.APPLICATION_JSON));

        RecordingListener listener = new RecordingListener();
        provider.awaitCompletion("sess_1", Duration.ofSeconds(30), 10, listener);

        assertThat(listener.results).containsExactly("bash|null|command not found|true");
        server.verify();
    }

    @Test
    void awaitCompletionReadsToolOutputFromAlternateFieldsAndNeverThrows() {
        // La forme exacte de `agent.tool_result` n'est pas documentée : formes alternatives et
        // event vide doivent être tolérés sans exception (un défaut d'affichage ne casse pas le run).
        server.expect(requestToUriTemplate(
                "https://api.anthropic.com/v1/sessions/sess_1/events?limit=1000"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"type\":\"agent.tool_result\",\"id\":\"e0\",\"name\":\"bash\","
                                + "\"stdout\":\"ok\",\"stderr\":\"warn\"},"
                                + "{\"type\":\"agent.tool_result\",\"id\":\"e1\",\"name\":\"bash\","
                                + "\"output\":\"depuis output\"},"
                                + "{\"type\":\"agent.tool_result\",\"id\":\"e2\"},"
                                + "{\"type\":\"session.status_idle\",\"id\":\"e3\",\"stop_reason\":\"end_turn\"}]}",
                        MediaType.APPLICATION_JSON));

        RecordingListener listener = new RecordingListener();
        provider.awaitCompletion("sess_1", Duration.ofSeconds(30), 10, listener);

        assertThat(listener.results).containsExactly(
                "bash|null|ok\nwarn|false",
                "bash|null|depuis output|false",
                "tool|null||false");
        server.verify();
    }

    @Test
    void awaitCompletionTruncatesVeryLargeToolOutput() {
        // Borne par défaut : 10 000 caractères. Un `npm install` non borné saturerait le flux SSE.
        String huge = "x".repeat(12_000);
        server.expect(requestToUriTemplate(
                "https://api.anthropic.com/v1/sessions/sess_1/events?limit=1000"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"type\":\"agent.tool_result\",\"id\":\"e0\",\"name\":\"bash\","
                                + "\"content\":\"" + huge + "\"},"
                                + "{\"type\":\"session.status_idle\",\"id\":\"e1\",\"stop_reason\":\"end_turn\"}]}",
                        MediaType.APPLICATION_JSON));

        RecordingListener listener = new RecordingListener();
        provider.awaitCompletion("sess_1", Duration.ofSeconds(30), 10, listener);

        assertThat(listener.results).hasSize(1);
        String output = listener.results.get(0).split("\\|")[2];
        assertThat(output).startsWith("x".repeat(10_000));
        assertThat(output).contains("2000 caractères omis");
        server.verify();
    }

    private static final class RecordingListener implements ManagedEventListener {
        private final List<String> texts = new java.util.ArrayList<>();
        private final List<String> actions = new java.util.ArrayList<>();
        private final List<String> states = new java.util.ArrayList<>();
        private final List<String> results = new java.util.ArrayList<>();
        private final List<String> actionIds = new java.util.ArrayList<>();

        @Override
        public void onAgentText(String text) {
            texts.add(text);
        }

        @Override
        public void onAction(String tool, String detail) {
            actions.add(tool + ":" + detail);
        }

        @Override
        public void onAction(String tool, String toolUseId, String detail) {
            actions.add(tool + ":" + detail);
            actionIds.add(String.valueOf(toolUseId));
        }

        @Override
        public void onActionResult(String tool, String toolUseId, String output, boolean error) {
            results.add(tool + "|" + toolUseId + "|" + output + "|" + error);
        }

        @Override
        public void onStatus(String state) {
            states.add(state);
        }
    }

    @Test
    void awaitCompletionThrowsTimeoutWhenNeverIdle() {
        // Chaque tour relit depuis la 1re page (running, jamais idle, pas de next_page → fin du tour).
        server.expect(ExpectedCount.manyTimes(), requestToUriTemplate(
                "https://api.anthropic.com/v1/sessions/sess_1/events?limit=1000"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"type\":\"session.status_running\",\"id\":\"e1\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.awaitCompletion("sess_1", Duration.ofSeconds(30), 2))
                .isInstanceOf(AgentSessionTimeoutException.class);
        server.verify();
    }

    @Test
    void listOutputsSendsBothBetaValuesAndParsesFiles() {
        server.expect(requestTo("https://api.anthropic.com/v1/files?scope_id=sess_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("scope_id", "sess_1"))
                // Les DEUX bêtas doivent être présentes sur l'en-tête anthropic-beta.
                .andExpect(header("anthropic-beta", BETA, FILES_BETA))
                // Un fichier d'entrée monté (downloadable=false) et une vraie sortie (downloadable=true) :
                // seul le second doit être retenu.
                .andRespond(withSuccess(
                        "{\"data\":[{\"id\":\"file_in\",\"filename\":\"src.js\",\"downloadable\":false},"
                                + "{\"id\":\"file_out\",\"filename\":\"result.txt\",\"downloadable\":true}]}",
                        MediaType.APPLICATION_JSON));

        List<OutputFile> outputs = provider.listOutputs("sess_1");

        assertThat(outputs).hasSize(1);
        assertThat(outputs.get(0).fileId()).isEqualTo("file_out");
        assertThat(outputs.get(0).filename()).isEqualTo("result.txt");
        server.verify();
    }

    @Test
    void downloadFileReturnsBytesWithFilesBeta() {
        server.expect(requestTo("https://api.anthropic.com/v1/files/file_out/content"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("anthropic-beta", FILES_BETA))
                .andRespond(withSuccess("contenu-sortie".getBytes(), MediaType.APPLICATION_OCTET_STREAM));

        byte[] bytes = provider.downloadFile("file_out");

        assertThat(new String(bytes)).isEqualTo("contenu-sortie");
        server.verify();
    }

    @Test
    void getSessionUsageAggregatesInputCacheAndRoundsActiveSeconds() {
        server.expect(requestTo("https://api.anthropic.com/v1/sessions/sess_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("anthropic-beta", BETA))
                .andRespond(withSuccess(
                        "{\"usage\":{\"cache_creation\":{\"ephemeral_1h_input_tokens\":0,"
                                + "\"ephemeral_5m_input_tokens\":465},\"cache_read_input_tokens\":14114,"
                                + "\"input_tokens\":4,\"output_tokens\":353},"
                                + "\"stats\":{\"active_seconds\":8.455,\"duration_seconds\":2142.2}}",
                        MediaType.APPLICATION_JSON));

        ManagedAgentProvider.SessionUsage usage = provider.getSessionUsage("sess_1");

        // input = input_tokens + cache_read + cache_creation(5m + 1h) = 4 + 14114 + 465 + 0 = 14583.
        assertThat(usage.inputTokens()).isEqualTo(14_583L);
        assertThat(usage.outputTokens()).isEqualTo(353L);
        // active_seconds 8.455 arrondi → 8.
        assertThat(usage.activeSeconds()).isEqualTo(8L);
        server.verify();
    }

    @Test
    void getSessionUsageTranslatesErrorToAgentProviderException() {
        server.expect(requestTo("https://api.anthropic.com/v1/sessions/sess_1"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> provider.getSessionUsage("sess_1"))
                .isInstanceOf(AgentProviderException.class);
        server.verify();
    }

    @Test
    void uploadFileTranslatesClientErrorToAgentProviderException() {
        server.expect(requestTo("https://api.anthropic.com/v1/files"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> provider.uploadFile("a.txt", "x".getBytes()))
                .isInstanceOf(AgentProviderException.class);
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
    // -------------------------------------- F-32 / SF-32-01 : interruption d'un run

    @Test
    void interruptSessionPostsUserInterruptEvent() {
        server.expect(requestTo("https://api.anthropic.com/v1/sessions/sess_1/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "sk-ant-test-key"))
                .andExpect(header("anthropic-beta", BETA))
                .andExpect(jsonPath("$.events[0].type").value("user.interrupt"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        provider.interruptSession("sess_1");

        server.verify();
    }

    @Test
    void interruptSessionSurfacesProviderFailure() {
        // Contrairement à `terminateSession`, l'échec n'est pas avalé : une interruption qui n'est
        // pas passée doit être dite, sinon l'utilisateur attend un arrêt qui ne viendra jamais.
        server.expect(requestTo("https://api.anthropic.com/v1/sessions/sess_dead/events"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> provider.interruptSession("sess_dead"))
                .isInstanceOf(AgentProviderException.class);

        server.verify();
    }
}
