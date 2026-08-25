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
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private volatile int repoStatusCode = 200;
    private volatile String repoResponseBody = "{\"full_name\":\"octocat/hello\",\"default_branch\":\"main\"}";

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
        server.createContext("/repos/", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestedPath.set(exchange.getRequestURI().getPath());
            byte[] payload = repoResponseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(repoStatusCode, payload.length);
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
                new GitProperties("http://127.0.0.1:" + port, Duration.ofSeconds(5), null, null, null, null),
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

    // ------------------------------------------------- F-31 / SF-31-02 : résolution du dépôt

    @Test
    void resolvesRepositoryAndItsDefaultBranch() {
        GitHubRepository repository = client().getRepository("github_pat_secret", "octocat", "hello");

        assertThat(repository.fullName()).isEqualTo("octocat/hello");
        assertThat(repository.defaultBranch()).isEqualTo("main");
        assertThat(requestedPath.get()).isEqualTo("/repos/octocat/hello");
        assertThat(authorizationHeader.get()).isEqualTo("Bearer github_pat_secret");
    }

    @Test
    void mapsRepositoryNotFoundToInvalidRepository() {
        // GitHub répond 404 aussi bien pour un dépôt inexistant que pour un dépôt hors de portée du
        // jeton : les deux cas sont volontairement confondus (ne pas divulguer l'existence d'un privé).
        repoStatusCode = 404;
        repoResponseBody = "{\"message\":\"Not Found\"}";

        assertThatThrownBy(() -> client().getRepository("github_pat_ok", "octocat", "secret"))
                .isInstanceOf(InvalidGitRepositoryException.class);
    }

    @Test
    void mapsRepositoryUnauthorizedToInvalidToken() {
        repoStatusCode = 401;
        repoResponseBody = "{\"message\":\"Bad credentials\"}";

        assertThatThrownBy(() -> client().getRepository("github_pat_bad", "octocat", "hello"))
                .isInstanceOf(InvalidGitTokenException.class);
    }

    @Test
    void mapsRepositoryServerErrorToUnavailable() {
        repoStatusCode = 503;
        repoResponseBody = "{\"message\":\"unavailable\"}";

        assertThatThrownBy(() -> client().getRepository("github_pat_ok", "octocat", "hello"))
                .isInstanceOf(GitHubUnavailableException.class);
    }

    // ------------------------------------------- F-31 / SF-31-03 : arborescence et lecture

    @Test
    void listsOnlyBlobsOfTheBranch() {
        repoResponseBody = """
                {"truncated": false, "tree": [
                  {"path": "src", "type": "tree"},
                  {"path": "src/App.java", "type": "blob"},
                  {"path": "README.md", "type": "blob"}
                ]}""";

        GitTreeListing listing = client().listTree("github_pat_ok", "octocat", "hello", "main", 100);

        // Les dossiers sont écartés : il n'y a rien à ouvrir dedans.
        assertThat(listing.paths()).containsExactly("src/App.java", "README.md");
        assertThat(listing.truncated()).isFalse();
        assertThat(requestedPath.get()).isEqualTo("/repos/octocat/hello/git/trees/main");
    }

    @Test
    void keepsBranchNamesContainingASlashIntact() {
        repoResponseBody = "{\"truncated\": false, \"tree\": []}";

        client().listTree("github_pat_ok", "octocat", "hello", "feat/atelier", 100);

        // Encoder le « / » rendrait la branche introuvable.
        assertThat(requestedPath.get()).isEqualTo("/repos/octocat/hello/git/trees/feat/atelier");
    }

    @Test
    void reportsTruncationFromGitHubAndFromOurOwnCap() {
        repoResponseBody = """
                {"truncated": true, "tree": [{"path": "a.txt", "type": "blob"}]}""";
        assertThat(client().listTree("github_pat_ok", "octocat", "hello", "main", 100).truncated()).isTrue();

        repoResponseBody = """
                {"truncated": false, "tree": [
                  {"path": "a.txt", "type": "blob"},
                  {"path": "b.txt", "type": "blob"}
                ]}""";
        GitTreeListing capped = client().listTree("github_pat_ok", "octocat", "hello", "main", 1);
        assertThat(capped.paths()).containsExactly("a.txt");
        assertThat(capped.truncated()).isTrue();
    }

    @Test
    void readsAndDecodesAFileOfTheBranch() {
        String encoded = java.util.Base64.getEncoder()
                .encodeToString("# Hello".getBytes(StandardCharsets.UTF_8));
        repoResponseBody = "{\"type\":\"file\",\"size\":7,\"content\":\"" + encoded + "\"}";

        String content = client().readFile("github_pat_ok", "octocat", "hello", "main", "docs/README.md", 1024);

        assertThat(content).isEqualTo("# Hello");
        assertThat(requestedPath.get()).isEqualTo("/repos/octocat/hello/contents/docs/README.md");
    }

    @Test
    void refusesABinaryFileRatherThanDecodingItAsText() {
        String encoded = java.util.Base64.getEncoder().encodeToString(new byte[] {1, 0, 2});
        repoResponseBody = "{\"type\":\"file\",\"size\":3,\"content\":\"" + encoded + "\"}";

        assertThatThrownBy(() ->
                client().readFile("github_pat_ok", "octocat", "hello", "main", "logo.png", 1024))
                .isInstanceOf(GitFileNotReadableException.class);
    }

    @Test
    void refusesAFileLargerThanTheLimit() {
        repoResponseBody = "{\"type\":\"file\",\"size\":9999,\"content\":\"\"}";

        assertThatThrownBy(() ->
                client().readFile("github_pat_ok", "octocat", "hello", "main", "big.bin", 10))
                .isInstanceOf(GitFileNotReadableException.class);
    }

    @Test
    void refusesAnEmptyContentFromTheApiRatherThanServingAnEmptyFile() {
        // Au-delà de sa propre limite, l'API renvoie la métadonnée sans contenu.
        repoResponseBody = "{\"type\":\"file\",\"size\":5,\"content\":\"\"}";

        assertThatThrownBy(() ->
                client().readFile("github_pat_ok", "octocat", "hello", "main", "big.bin", 10_000))
                .isInstanceOf(GitFileNotReadableException.class);
    }

    @Test
    void treatsADirectoryAsAMissingFile() {
        repoResponseBody = "{\"type\":\"dir\"}";

        assertThatThrownBy(() ->
                client().readFile("github_pat_ok", "octocat", "hello", "main", "src", 1024))
                .isInstanceOf(InvalidGitRepositoryException.class);
    }

    @Test
    void rejectsRepositoryWithoutDefaultBranch() {
        // Dépôt vide : rien à monter dans la sandbox, rien à comparer. Mieux vaut le dire tout de suite.
        repoResponseBody = "{\"full_name\":\"octocat/empty\"}";

        assertThatThrownBy(() -> client().getRepository("github_pat_ok", "octocat", "empty"))
                .isInstanceOf(InvalidGitRepositoryException.class);
    }
}
