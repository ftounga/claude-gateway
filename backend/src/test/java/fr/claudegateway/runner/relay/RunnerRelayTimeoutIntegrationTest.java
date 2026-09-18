package fr.claudegateway.runner.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.channel.RemoteRunnerNode;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerRegistry;
import fr.claudegateway.runner.channel.RunnerTarget;

/**
 * Défaut tracé en production le 2026-09-18 (SF-38-28) : sur les gros tours, le relais NDJSON
 * {@code /internal/runner/call} rendait une erreur brute — une {@code AsyncRequestTimeoutException}
 * tuait l'appel avant son délai d'outil, puis le chemin d'erreur tentait d'écrire un
 * {@code ErrorResponse} objet sur un flux {@code application/x-ndjson}
 * ({@code HttpMessageNotWritableException}).
 *
 * <p>Le harnais est celui de la production, comme {@link RunnerRelayStreamIntegrationTest} : les deux
 * « pods » sont le même contexte, joints par le vrai connecteur interne, et le chemin traversé
 * (contrôleur NDJSON, {@code StreamingResponseBody}, {@code WebAsyncManager}, lecture ligne à ligne
 * du client) est réel. Le délai async <b>global</b> est volontairement fixé très court
 * ({@code spring.mvc.async.request-timeout=800}) : sans l'override par requête, un appel de 1,5 s
 * serait tué — c'est précisément ce que ce test empêche de régresser.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.runner.relay.secret=secret-de-relais-de-test-32-octets!!",
                "app.runner.relay.port=0",
                // Défaut async GLOBAL délibérément court : il tuerait tout appel > 800 ms sans
                // l'override par endpoint de SF-38-28.
                "spring.mvc.async.request-timeout=800"
        })
@ActiveProfiles("test")
class RunnerRelayTimeoutIntegrationTest {

    /** Outil « lent » : plus long que le défaut async global, plus court que le délai d'outil. */
    private static final String SLOW_TOOL = "slow";
    /** Outil « qui casse » : le dispatcher lève, pour éprouver le chemin d'erreur du flux. */
    private static final String BOOM_TOOL = "boom";
    /** Pause du dispatcher lent : > 800 ms (défaut global) et << timeoutMs demandé. */
    private static final long SLOW_WORK_MS = 1_500L;
    /** Délai d'outil demandé : l'override async doit valoir timeoutMs + marge (donc >> 800 ms). */
    private static final long TOOL_TIMEOUT_MS = 30_000L;

    @Autowired
    private RunnerRelayConnectorCustomizer relayConnector;

    @Autowired
    private RunnerRelayClient relayClient;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void waitForTheRelayConnectorToBeBound() {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket probe = new java.net.Socket()) {
                probe.connect(new java.net.InetSocketAddress("127.0.0.1", relayConnector.relayPort()),
                        200);
                return;
            } catch (java.io.IOException notYet) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        throw new IllegalStateException("Le connecteur de relais n'écoute toujours pas après 10 s.");
    }

    private RemoteRunnerNode node() {
        return new RemoteRunnerNode("pod-voisin", "http://127.0.0.1:" + relayConnector.relayPort());
    }

    private RunnerTarget target() {
        return new RunnerTarget(UUID.randomUUID(), UUID.randomUUID(), "projet");
    }

    @Test
    void longCallIsNotKilledByTheDefaultAsyncTimeout() {
        long start = System.nanoTime();
        RunnerCallResult result = relayClient.call(node(), target(), "toolu_lent", SLOW_TOOL,
                objectMapper.createObjectNode(), TOOL_TIMEOUT_MS, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // Sans l'override async par requête, le défaut global (800 ms) aurait tué l'appel bien avant
        // ses 1,5 s de travail : le client aurait lu un flux coupé (runner_unavailable). Ici l'issue
        // arrive normalement.
        assertThat(result.ok())
                .as("appel long tué prématurément : code=%s, message=%s", result.errorCode(),
                        result.errorMessage())
                .isTrue();
        assertThat(result.content()).isEqualTo("terminé");
        // Preuve que l'appel a bien travaillé au-delà du défaut async global, sans être coupé.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(SLOW_WORK_MS);
    }

    @Test
    void unexpectedFailureIsRenderedAsATerminalNdjsonLine() {
        RunnerCallResult result = relayClient.call(node(), target(), "toolu_casse", BOOM_TOOL,
                objectMapper.createObjectNode(), TOOL_TIMEOUT_MS, null);

        // Le défaut : l'exception remontait à l'@ExceptionHandler, qui écrivait un ErrorResponse
        // objet sur le flux x-ndjson (HttpMessageNotWritableException) → flux cassé, l'utilisateur
        // voyait une erreur brute. Le client aurait alors lu « flux coupé » (runner_unavailable) ou
        // « pair injoignable » (runner_not_on_this_node), jamais ce code+message précis.
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(RunnerErrorCodes.RUNNER_PROTOCOL_ERROR);
        assertThat(result.errorMessage()).isEqualTo("L'appel relayé n'a pas pu être finalisé.");
    }

    /**
     * Dispatcher de substitution : il joue le pod qui tient la socket, et branche sur le nom d'outil
     * pour éprouver soit un appel long (SF-38-28 défaut n°1), soit une défaillance inattendue en
     * cours de flux (SF-38-28 défaut n°2).
     */
    @TestConfiguration
    static class BranchingDispatcherConfig {

        @Bean
        @Primary
        RunnerCallDispatcher branchingDispatcher(RunnerRegistry registry, ObjectMapper objectMapper) {
            return new RunnerCallDispatcher(registry, objectMapper, (id, shell) -> { },
                    (id, version) -> { }, new fr.claudegateway.runner.ServedRunnerVersion("", ""),
                    fr.claudegateway.runner.RunnerLivenessStubs.alwaysAlive(), 5_000L) {
                @Override
                public RunnerCallResult call(RunnerTarget target, String callId, String tool,
                        JsonNode input, long timeoutMs, Consumer<String> onChunk) {
                    if (BOOM_TOOL.equals(tool)) {
                        throw new IllegalStateException("panne simulée du dispatcher");
                    }
                    if (SLOW_TOOL.equals(tool)) {
                        sleep(SLOW_WORK_MS);
                        return new RunnerCallResult(true, "terminé", false, 0, SLOW_WORK_MS, null,
                                null, null, "", false);
                    }
                    return new RunnerCallResult(true, "", false, 0, 0L, null, null, null, "", false);
                }

                private void sleep(long ms) {
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
        }
    }
}
