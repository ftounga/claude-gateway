package fr.claudegateway.runner.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;

/**
 * Le relais d'un <b>tour</b> entre pods, bout à bout (F-84 / SF-84-02).
 *
 * <p>C'est ce qui rend un tour observable depuis un autre pod : le spectateur arrive sur un replica
 * quelconque, ce replica sonde ses pairs, et celui qui exécute rend son flux. Sans ces deux routes,
 * rouvrir un terminal sous HPA rendrait « rien ne tourne » une fois sur deux — un mensonge, pas une
 * dégradation.</p>
 *
 * <p>Les trois barrières de SF-38-12 sont revérifiées : port public ⇒ 404, pas de secret ⇒ 401.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.runner.relay.secret=secret-de-relais-de-test-32-octets!!",
                "app.runner.relay.port=0"
        })
@ActiveProfiles("test")
class AtelierTurnRelayApiIntegrationTest {

    private static final String SECRET = "secret-de-relais-de-test-32-octets!!";
    private static final String OWNER = "/api/internal/atelier/turn-owner";
    private static final String STREAM = "/api/internal/atelier/turn-stream";

    @LocalServerPort
    private int publicPort;

    @Autowired
    private RunnerRelayConnectorCustomizer relayConnector;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private LiveTurnRegistry liveTurns;

    @Test
    void unPodQuiNexecutePasCeTourNeSenDitPasProprietaire() {
        ResponseEntity<String> response = post(relayPort(), OWNER, SECRET,
                payload(UUID.randomUUID(), UUID.randomUUID(), 0L));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"owner\":false");
    }

    @Test
    void leProprietaireSeNommeEtDonneSonCurseur() {
        UUID user = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        LiveTurn turn = liveTurns.open(user, workspace);
        try {
            turn.publish("text", new Payload("un"));

            ResponseEntity<String> response = post(relayPort(), OWNER, SECRET,
                    payload(user, workspace, 0L));

            assertThat(response.getBody()).contains("\"owner\":true");
            assertThat(response.getBody()).contains(turn.turnId().toString());
            assertThat(response.getBody()).contains("\"cursor\":1");
        } finally {
            liveTurns.close(turn);
        }
    }

    @Test
    void leTourDunAutreUtilisateurResteIntrouvable() {
        UUID workspace = UUID.randomUUID();
        LiveTurn turn = liveTurns.open(UUID.randomUUID(), workspace);
        try {
            ResponseEntity<String> response = post(relayPort(), OWNER, SECRET,
                    payload(UUID.randomUUID(), workspace, 0L));

            assertThat(response.getBody()).contains("\"owner\":false");
        } finally {
            liveTurns.close(turn);
        }
    }

    @Test
    void leFluxRelayePorteLeRejeuPuisLaLigneDeFin() {
        UUID user = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        LiveTurn turn = liveTurns.open(user, workspace);
        turn.publish("text", new Payload("un"));
        turn.publish("action", new Payload("deux"));
        // Le tour se termine pendant que le pair lit : c'est la ligne `end` qui rend la main.
        CompletableFuture.runAsync(() -> {
            sleep(300);
            liveTurns.close(turn);
        });

        ResponseEntity<String> response = post(relayPort(), STREAM, SECRET,
                payload(user, workspace, 0L));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        assertThat(body).contains("\"attached\":true");
        assertThat(body).contains("\"seq\":1").contains("\"seq\":2");
        assertThat(body).contains("\"type\":\"end\"");
    }

    @Test
    void unFluxSansTourVivantLeDitEtSeClot() {
        ResponseEntity<String> response = post(relayPort(), STREAM, SECRET,
                payload(UUID.randomUUID(), UUID.randomUUID(), 0L));

        assertThat(response.getBody()).contains("\"attached\":false");
        assertThat(response.getBody()).contains("\"type\":\"end\"");
    }

    @Test
    void lesRoutesDeTourNeSontPasJoignablesDepuisLePortPublic() {
        ResponseEntity<String> response = post(publicPort, OWNER, SECRET,
                payload(UUID.randomUUID(), UUID.randomUUID(), 0L));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void sansSecretLeRelaisDeTourEstRefuse() {
        ResponseEntity<String> response = post(relayPort(), OWNER, null,
                payload(UUID.randomUUID(), UUID.randomUUID(), 0L));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ outillage

    private int relayPort() {
        return relayConnector.relayPort();
    }

    private String payload(UUID userId, UUID workspaceId, long cursor) {
        return "{\"userId\":\"" + userId + "\",\"workspaceId\":\"" + workspaceId
                + "\",\"cursor\":" + cursor + "}";
    }

    private ResponseEntity<String> post(int port, String path, String secret, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_NDJSON));
        if (secret != null) {
            headers.set(RunnerRelayAuthFilter.SECRET_HEADER, secret);
        }
        return restTemplate.exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private record Payload(String text) {
    }
}
