package fr.claudegateway.runner.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.runner.launcher.LauncherHome;

/** F-111 / SF-111-03 — télécharger par le client du runner, vérifier, puis seulement installer. */
class UpdateInstallerTest {

    private static final String ID = "1.1.0-202609200900-bbb2222";

    @TempDir
    Path dir;

    private HttpServer server;
    private final Map<String, byte[]> routes = new HashMap<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private KeyPair keys;
    private byte[] jar;

    @BeforeEach
    void setUp() throws Exception {
        keys = TestSigning.keyPair();
        jar = TestSigning.jarOf("1.1.0", "202609200900", "bbb2222");
        serve(jar, LauncherHome.sha256(jar), TestSigning.sign(keys.getPrivate(), jar));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            byte[] body = routes.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void installeUneVersionSigneeApresVerification() throws Exception {
        LauncherHome home = new LauncherHome(dir.resolve("home"));

        Path installed = installer(home).install(ID, LauncherHome.sha256(jar));

        assertTrue(home.isInstalled(ID));
        assertEquals(home.jarOf(ID), installed);
        assertTrue(requested.containsAll(List.of("/api/runner/update/" + ID, "/api/runner/update/" + ID + "/sha256",
                "/api/runner/update/" + ID + "/signature")), requested.toString());
    }

    @Test
    void uneSignatureInvalideNEcritRienDansVersions() throws Exception {
        serve(jar, LauncherHome.sha256(jar), TestSigning.sign(TestSigning.keyPair().getPrivate(), jar));
        LauncherHome home = new LauncherHome(dir.resolve("home"));

        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> installer(home).install(ID, null));

        assertEquals(UpdateRejectedException.SIGNATURE_INVALID, e.reason());
        assertFalse(Files.exists(dir.resolve("home").resolve("versions")), "aucune écriture avant vérification");
    }

    @Test
    void uneEmpreinteServieDifferenteDeLaCommandeEstRefusee() {
        LauncherHome home = new LauncherHome(dir.resolve("home"));
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> installer(home).install(ID, "ab".repeat(32)));
        assertEquals(UpdateRejectedException.SHA256_MISMATCH, e.reason());
        assertFalse(home.isInstalled(ID));
    }

    @Test
    void uneVersionAbsenteDeLaGatewayEstRefusee() {
        routes.clear();
        LauncherHome home = new LauncherHome(dir.resolve("home"));
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> installer(home).install(ID, null));
        assertEquals(UpdateRejectedException.DOWNLOAD_FAILED, e.reason());
    }

    @Test
    void unIdentifiantQuiNEstPasUneVersionNeQuitteMemePasLeRunner() {
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> installer(new LauncherHome(dir)).install("../../etc/passwd", null));
        assertEquals(UpdateRejectedException.VERSION_MISMATCH, e.reason());
        assertTrue(requested.isEmpty());
    }

    private UpdateInstaller installer(LauncherHome home) {
        return new UpdateInstaller(HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/", new UpdateVerifier(keys.getPublic()),
                home);
    }

    private void serve(byte[] body, String sha, String signature) throws IOException {
        routes.put("/api/runner/update/" + ID, body);
        routes.put("/api/runner/update/" + ID + "/sha256", (sha + "\n").getBytes(StandardCharsets.US_ASCII));
        routes.put("/api/runner/update/" + ID + "/signature", (signature + "\n").getBytes(StandardCharsets.US_ASCII));
    }
}
