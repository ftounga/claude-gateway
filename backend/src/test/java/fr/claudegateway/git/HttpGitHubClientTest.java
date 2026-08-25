package fr.claudegateway.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

/**
 * Tests de {@link HttpGitHubClient} contre un serveur HTTP local : traduction des statuts GitHub en
 * exceptions métier (jeton refusé vs indisponibilité temporaire) et transmission du jeton en
 * en-tête {@code Authorization}. Aucun appel vers l'Internet.
 */
class HttpGitHubClientTest {

    private HttpServer server;
    private final AtomicReference<String> authorizationHeader = new AtomicReference<>();
    private volatile int statusCode = 200;
    private volatile String responseBody = "{\"login\":\"octocat\"}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private GitHubClient client() {
        int port = server.getAddress().getPort();
        return new HttpGitHubClient(
                new GitProperties("http://127.0.0.1:" + port, Duration.ofSeconds(5)),
                RestClient.builder());
    }

    @Test
    void returnsAccountAndSendsTokenAsBearer() {
        GitHubAccount account = client().verifyToken("github_pat_secret");

        assertThat(account.login()).isEqualTo("octocat");
        assertThat(authorizationHeader.get()).isEqualTo("Bearer github_pat_secret");
    }

    @Test
    void mapsUnauthorizedToInvalidToken() {
        statusCode = 401;
        responseBody = "{\"message\":\"Bad credentials\"}";

        assertThatThrownBy(() -> client().verifyToken("github_pat_bad"))
                .isInstanceOf(InvalidGitTokenException.class);
    }

    @Test
    void mapsForbiddenToInvalidToken() {
        statusCode = 403;
        responseBody = "{\"message\":\"Forbidden\"}";

        assertThatThrownBy(() -> client().verifyToken("github_pat_bad"))
                .isInstanceOf(InvalidGitTokenException.class);
    }

    @Test
    void mapsServerErrorToUnavailable() {
        statusCode = 502;
        responseBody = "{\"message\":\"Bad gateway\"}";

        assertThatThrownBy(() -> client().verifyToken("github_pat_ok"))
                .isInstanceOf(GitHubUnavailableException.class);
    }

    @Test
    void mapsNetworkFailureToUnavailable() {
        GitHubClient client = client();
        server.stop(0);

        assertThatThrownBy(() -> client.verifyToken("github_pat_ok"))
                .isInstanceOf(GitHubUnavailableException.class);
    }
}
