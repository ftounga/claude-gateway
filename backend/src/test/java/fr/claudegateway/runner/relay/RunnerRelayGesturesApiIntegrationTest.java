package fr.claudegateway.runner.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.runner.exec.RunnerConfirmationGate;

/**
 * Les gestes diffusés du relais interne, bout à bout (F-38 / SF-38-13) : annuler, trancher une
 * autorisation, interrompre un tour, marquer une session.
 *
 * <p>Le test qui compte est {@code aRelayedDecisionReachesTheGateThatWaits} : sans lui, une
 * autorisation donnée sur un pod n'atteindrait jamais la porte qui attend sur l'autre, et
 * <b>toutes</b> les commandes finiraient refusées au bout de 120 s dès qu'un second replica
 * existe.</p>
 *
 * <p>Les trois barrières de SF-38-12 sont revérifiées sur ces nouvelles routes : port public ⇒ 404,
 * pas de secret ⇒ 401, corps vides dans les deux cas.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.runner.relay.secret=secret-de-relais-de-test-32-octets!!",
                "app.runner.relay.port=0"
        })
@ActiveProfiles("test")
class RunnerRelayGesturesApiIntegrationTest {

    private static final String SECRET = "secret-de-relais-de-test-32-octets!!";
    private static final String CANCEL = "/api/internal/runner/cancel";
    private static final String CONFIRM = "/api/internal/runner/confirm";
    private static final String INTERRUPT = "/api/internal/atelier/interrupt";
    private static final String SESSION_INTERRUPT = "/api/internal/atelier/session-interrupt";

    @LocalServerPort
    private int publicPort;

    @Autowired
    private RunnerRelayConnectorCustomizer relayConnector;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RunnerConfirmationGate confirmationGate;

    private ResponseEntity<String> post(int port, String path, String secret, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (secret != null) {
            headers.set(RunnerRelayAuthFilter.SECRET_HEADER, secret);
        }
        return restTemplate.exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> relay(String path, String body) {
        return post(relayConnector.relayPort(), path, SECRET, body);
    }

    @Test
    void cancellingOnAPodWithoutTheSocketIsNotAnError() {
        ResponseEntity<String> response = relay(CANCEL,
                "{\"workspaceId\":\"%s\",\"reason\":\"user_interrupt\"}".formatted(UUID.randomUUID()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"cancelled\":0");
    }

    @Test
    void aRelayedDecisionReachesTheGateThatWaits() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String callId = "toolu_relais_" + UUID.randomUUID();
        CompletableFuture<Boolean> registered = new CompletableFuture<>();
        CompletableFuture<RunnerConfirmationGate.Outcome> decided = CompletableFuture.supplyAsync(
                () -> confirmationGate.await(userId, workspaceId, callId,
                        () -> registered.complete(true)));
        registered.get(5, TimeUnit.SECONDS);

        ResponseEntity<String> response = relay(CONFIRM,
                """
                {"userId":"%s","workspaceId":"%s","callId":"%s","allow":true,"reason":"vas-y"}
                """.formatted(userId, workspaceId, callId));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"resolved\":true");
        RunnerConfirmationGate.Outcome outcome = decided.get(5, TimeUnit.SECONDS);
        assertThat(outcome.decision()).isEqualTo(RunnerConfirmationGate.Decision.ALLOW);
        assertThat(outcome.reason()).isEqualTo("vas-y");
    }

    @Test
    void aDecisionForAGateThisPodDoesNotHoldIsAnsweredWithoutError() {
        // Le cas de tous les pods sauf un : « ce n'est pas moi qui attendais » n'est pas une erreur
        // de transport, sinon la diffusion serait illisible.
        ResponseEntity<String> response = relay(CONFIRM,
                """
                {"userId":"%s","workspaceId":"%s","callId":"toolu_inconnu","allow":true,"reason":null}
                """.formatted(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"resolved\":false");
    }

    @Test
    void aDecisionFromAnotherUserNeverResolvesTheGate() throws Exception {
        // Isolation : la porte compare userId ET workspaceId — un identifiant de corrélation deviné
        // n'autorise rien chez autrui, y compris quand la décision arrive par le relais.
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String callId = "toolu_isolation_" + UUID.randomUUID();
        CompletableFuture<Boolean> registered = new CompletableFuture<>();
        CompletableFuture<RunnerConfirmationGate.Outcome> decided = CompletableFuture.supplyAsync(
                () -> confirmationGate.await(userId, workspaceId, callId,
                        () -> registered.complete(true)));
        registered.get(5, TimeUnit.SECONDS);

        ResponseEntity<String> response = relay(CONFIRM,
                """
                {"userId":"%s","workspaceId":"%s","callId":"%s","allow":true,"reason":null}
                """.formatted(UUID.randomUUID(), workspaceId, callId));

        assertThat(response.getBody()).contains("\"resolved\":false");
        assertThat(decided.isDone()).isFalse();
        // Libération pour ne pas laisser le tour attendre l'échéance de 120 s.
        confirmationGate.cancelWorkspace(workspaceId);
        assertThat(decided.get(5, TimeUnit.SECONDS).decision())
                .isEqualTo(RunnerConfirmationGate.Decision.DENY);
    }

    @Test
    void aRelayedInterruptIsAppliedAndReported() {
        ResponseEntity<String> response = relay(INTERRUPT,
                """
                {"userId":"%s","workspaceId":"%s","reason":"user_interrupt"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"marked\":true").contains("\"cancelled\":0");
    }

    @Test
    void aRelayedSessionMarkIsSetThenRetracted() {
        assertThat(relay(SESSION_INTERRUPT, "{\"sessionId\":\"sess_1\",\"mark\":true}").getBody())
                .contains("\"marked\":true");
        assertThat(relay(SESSION_INTERRUPT, "{\"sessionId\":\"sess_1\",\"mark\":false}").getBody())
                .contains("\"marked\":false");
    }

    @Test
    void anIncompleteEnvelopeIsRefusedWithoutABody() {
        assertThat(relay(CONFIRM, "{\"userId\":null,\"workspaceId\":null}").getStatusCode().value())
                .isEqualTo(400);
        assertThat(relay(SESSION_INTERRUPT, "{\"sessionId\":\"  \",\"mark\":true}")
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void theNewRoutesAreUnreachableFromThePublicPortEvenWithAValidSecret() {
        for (String path : List.of(CANCEL, CONFIRM, INTERRUPT, SESSION_INTERRUPT)) {
            ResponseEntity<String> response = post(publicPort, path, SECRET, "{}");

            assertThat(response.getStatusCode().value()).as("chemin %s", path).isEqualTo(404);
            assertThat(response.getBody()).isNull();
        }
    }

    @Test
    void theNewRoutesRefuseAnyCallerWithoutTheSharedSecret() {
        for (String path : List.of(CANCEL, CONFIRM, INTERRUPT, SESSION_INTERRUPT)) {
            ResponseEntity<String> response =
                    post(relayConnector.relayPort(), path, null, "{}");

            assertThat(response.getStatusCode().value()).as("chemin %s", path).isEqualTo(401);
            assertThat(response.getBody()).isNull();
        }
    }
}
