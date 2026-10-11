package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>Le client réseau unique du runner</b> (F-190 / SF-190-06) : derrière Zscaler, le rapatriement
 * de ffmpeg échouait (« PKIX path building failed ») parce qu'il créait son propre client, sans le
 * proxy ni la racine d'entreprise du runner.
 */
class RunnerHttpTest {

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        RunnerHttp.reset();
    }

    @Test
    void sans_client_pose_un_client_simple_est_rendu_et_une_fois_pose_cest_lui() {
        assertFalse(RunnerHttp.configured());
        HttpClient fallback = RunnerHttp.client();
        assertSame(fallback, RunnerHttp.client(), "le repli est construit une fois");

        HttpClient runner = HttpClient.newBuilder().build();
        RunnerHttp.use(runner);

        assertSame(runner, RunnerHttp.client());
    }

    @Test
    void le_rapatriement_de_ffmpeg_passe_par_le_proxy_du_runner() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0)) {
            CompletableFuture<String> firstLine = CompletableFuture.supplyAsync(() -> {
                try (Socket client = proxy.accept()) {
                    byte[] buffer = new byte[256];
                    int read = client.getInputStream().read(buffer);
                    return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.US_ASCII);
                } catch (IOException e) {
                    return "";
                }
            });
            RunnerHttp.use(HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", proxy.getLocalPort())))
                    .build());

            try {
                fr.claudegateway.runner.teams.LocalToolchainAccess.download(
                        "https://ffmpeg.invalid/ffmpeg-release-amd64-static.tar.xz", dir.resolve("a.tar.xz"));
            } catch (IOException expected) {
                // Le faux proxy ferme la connexion : seul compte qu'il ait été contacté.
            }

            String request = firstLine.get(10, TimeUnit.SECONDS);
            assertTrue(request.startsWith("CONNECT ffmpeg.invalid:443"),
                    "le téléchargement doit passer par le client du runner (proxy) : " + request);
        }
    }

    /**
     * La garde : plus aucun client réseau nu dans le code du runner, hors la boucle locale (Chrome
     * piloté en 127.0.0.1, qui ne doit passer par aucun proxy) et le constructeur du client unique.
     */
    @Test
    void aucun_client_reseau_nu_hors_de_la_boucle_locale() throws IOException {
        Path sources = Path.of("src/main/java/fr/claudegateway/runner");
        List<String> allowed = List.of("RunnerMain.java", "RunnerHttp.java", "BrowserLink.java",
                "TeamsTabOpener.java", "WebSocketCdpConnection.java");
        try (Stream<Path> files = Files.walk(sources)) {
            List<String> offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !allowed.contains(path.getFileName().toString()))
                    .filter(path -> {
                        try {
                            String text = Files.readString(path);
                            return text.contains("HttpClient.newHttpClient()")
                                    || text.contains("HttpClient.newBuilder()");
                        } catch (IOException e) {
                            return true;
                        }
                    })
                    .map(path -> path.getFileName().toString())
                    .toList();
            assertTrue(offenders.isEmpty(), "client réseau nu (sans proxy ni racine d'entreprise) : "
                    + offenders + " — utilise RunnerHttp.client()");
        }
    }
}
