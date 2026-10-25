package fr.claudegateway.runner.video.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fr.claudegateway.runner.OperatingSystem;
import fr.claudegateway.runner.update.UpdateVerifier;

/**
 * La chaîne vidéo servie par le portail (F-215 / SF-215-06), côté runner : un VRAI serveur HTTP local imite les
 * routes de la gateway — manifeste signé, signature, paquet avec {@code Range}.
 */
class VideoToolchainBundleTest {

    private static final String VERSION = VideoToolchainBundle.VERSION;

    @TempDir Path temp;

    private HttpServer server;
    private KeyPair keys;
    private byte[] bundle;
    private byte[] manifest;
    private String signature;
    private int manifestStatus = 200;
    private int bundleStatus = 200;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> ranges = Collections.synchronizedList(new ArrayList<>());
    private final List<String> tokens = Collections.synchronizedList(new ArrayList<>());
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        bundle = TarFixtures.linuxBundle(VERSION).tarGz();
        publish(bundle, VideoToolchainBundle.sha256(write(bundle)), bundle.length, "0.8.193");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "/api" + VideoToolchainBundle.ROUTE + VERSION;
        server.createContext(base + "/manifest", ex -> respond(ex, manifestStatus, manifest));
        server.createContext(base + "/signature", ex -> respond(ex, 200, signature.getBytes(StandardCharsets.US_ASCII)));
        server.createContext(base + "/linux-x64", this::serveBundle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private Path write(byte[] bytes) throws IOException {
        Path file = Files.createTempFile(temp, "b", ".bin");
        Files.write(file, bytes);
        return file;
    }

    private void publish(byte[] payload, String sha256, long size, String hyperframes) throws Exception {
        manifest = ("{\"kind\":\"video-toolchain\",\"version\":\"" + VERSION + "\",\"hyperframes\":\"" + hyperframes
                + "\",\"bundles\":{\"linux-x64\":{\"file\":\"linux-x64.tar.gz\",\"sha256\":\"" + sha256
                + "\",\"size\":" + size + "}}}").getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(manifest);
        signature = Base64.getEncoder().encodeToString(signer.sign());
        bundle = payload;
    }

    private void serveBundle(HttpExchange ex) throws IOException {
        requests.add(ex.getRequestURI().getPath());
        tokens.add(ex.getRequestHeaders().getFirst("X-Runner-Token"));
        String range = ex.getRequestHeaders().getFirst("Range");
        ranges.add(range);
        if (bundleStatus != 200) {
            respond(ex, bundleStatus, "{}".getBytes());
            return;
        }
        if (range != null) {
            int from = Integer.parseInt(range.replace("bytes=", "").replace("-", ""));
            byte[] rest = Arrays.copyOfRange(bundle, from, bundle.length);
            ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (bundle.length - 1) + "/" + bundle.length);
            respond(ex, 206, rest);
            return;
        }
        respond(ex, 200, bundle);
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private VideoToolchainBundle bundle(String token) {
        return bundle(token, "http://127.0.0.1:" + server.getAddress().getPort() + "/api", "amd64");
    }

    private VideoToolchainBundle bundle(String token, String baseUrl, String arch) {
        UpdateVerifier verifier = new UpdateVerifier(keys.getPublic());
        return new VideoToolchainBundle(temp.resolve("tools"), OperatingSystem.LINUX, arch, baseUrl, () -> token,
                HttpClient::newHttpClient, verifier::signatureValid);
    }

    @Test
    void nominal_manifesteSigneEmpreinteExtractionMarqueur() {
        VideoToolchainBundle portal = bundle("jeton-du-poste");

        VideoToolchainBundle.Installed installed = portal.ensure(said::add);

        assertTrue(Files.isRegularFile(installed.node()));
        assertTrue(Files.isRegularFile(installed.hyperframes()));
        assertTrue(Files.isRegularFile(installed.gsap()));
        assertTrue(Files.isRegularFile(installed.ffmpeg()));
        assertTrue(Files.isRegularFile(installed.ffprobe()));
        assertEquals(temp.resolve("tools/video-toolchain-" + VERSION), installed.home());
        assertTrue(portal.installed().isPresent(), "marqueur posé : en place");
        assertEquals(List.of("jeton-du-poste"), tokens, "le jeton du poste accompagne la demande");
        assertTrue(said.get(0).contains("je récupère la chaîne vidéo depuis le portail"), said.toString());
        assertTrue(said.get(0).contains("une seule fois"), said.toString());
        assertTrue(said.get(said.size() - 1).contains("en place"), said.toString());
        assertFalse(Files.exists(temp.resolve("tools/video-toolchain-" + VERSION + "-linux-x64.tar.gz.part")),
                "l'archive téléchargée est supprimée après l'installation");

        portal.ensure(said::add);
        assertEquals(1, requests.size(), "une seule fois : déjà en place, rien n'est retéléchargé");
    }

    @Test
    void unTelechargementInterrompuReprendParRange() throws IOException {
        Path part = Files.createDirectories(temp.resolve("tools"))
                .resolve("video-toolchain-" + VERSION + "-linux-x64.tar.gz.part");
        Files.write(part, Arrays.copyOf(bundle, bundle.length / 2));

        bundle("jeton").ensure(said::add);

        assertEquals(List.of("bytes=" + (bundle.length / 2) + "-"), ranges);
        assertTrue(said.stream().anyMatch(s -> s.contains("Reprise du téléchargement")), said.toString());
    }

    @Test
    void uneSignatureInvalideNeTelechargeNiNInstalleRien() {
        signature = Base64.getEncoder().encodeToString(new byte[64]);

        RenderFailure failure = assertThrows(RenderFailure.class, () -> bundle("jeton").ensure(said::add));

        assertTrue(failure.getMessage().contains("signature"), failure.getMessage());
        assertTrue(requests.isEmpty(), "le paquet n'est même pas demandé");
        assertFalse(Files.exists(temp.resolve("tools/video-toolchain-" + VERSION)));
    }

    @Test
    void unPaquetAltereEstSupprimeEtRefuse() throws Exception {
        String genuine = VideoToolchainBundle.sha256(write(bundle));
        byte[] altered = bundle.clone();
        altered[altered.length - 30] ^= 0x55;
        publish(altered, genuine, altered.length, "0.8.193");

        RenderFailure failure = assertThrows(RenderFailure.class, () -> bundle("jeton").ensure(said::add));

        assertTrue(failure.getMessage().contains("altéré ou tronqué"), failure.getMessage());
        assertFalse(Files.exists(temp.resolve("tools/video-toolchain-" + VERSION + "-linux-x64.tar.gz.part")));
        assertFalse(Files.exists(temp.resolve("tools/video-toolchain-" + VERSION)));
    }

    @Test
    void jetonRefuseManifesteAbsentPortailInjoignableEtSansJeton() throws IOException {
        manifestStatus = 401;
        RenderFailure refused = assertThrows(RenderFailure.class, () -> bundle("jeton").ensure(said::add));
        assertTrue(refused.getMessage().contains("refusé le jeton"), refused.getMessage());
        assertTrue(refused.remedy().contains("Réappairer"), refused.remedy());

        manifestStatus = 404;
        RenderFailure absent = assertThrows(RenderFailure.class, () -> bundle("jeton").ensure(said::add));
        assertTrue(absent.getMessage().contains("ne sert pas la chaîne vidéo"), absent.getMessage());
        assertTrue(absent.remedy().contains("administrateur du portail"), absent.remedy());

        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        RenderFailure unreachable = assertThrows(RenderFailure.class,
                () -> bundle("jeton", "http://127.0.0.1:" + deadPort + "/api", "amd64").ensure(said::add));
        assertTrue(unreachable.getMessage().contains("n'a pas pu être joint"), unreachable.getMessage());

        RenderFailure noToken = assertThrows(RenderFailure.class, () -> bundle("").ensure(said::add));
        assertTrue(noToken.getMessage().contains("pas de jeton runner"), noToken.getMessage());

        for (RenderFailure f : List.of(refused, absent, unreachable, noToken)) {
            String text = f.getMessage() + " " + f.remedy();
            assertFalse(text.contains("brew") || text.contains("nodejs.org"), text);
        }
    }

    @Test
    void lePaquetDUnePlateformeAbsenteEstNomme() {
        bundleStatus = 404;
        RenderFailure failure = assertThrows(RenderFailure.class, () -> bundle("jeton").ensure(said::add));
        assertTrue(failure.getMessage().contains("pour linux-x64"), failure.getMessage());
    }

    @Test
    void unManifesteDUneAutreVersionDHyperframesEstRefuse() throws Exception {
        publish(bundle, VideoToolchainBundle.sha256(write(bundle)), bundle.length, "0.9.0");
        RenderFailure failure = assertThrows(RenderFailure.class, () -> bundle("jeton").ensure(said::add));
        assertTrue(failure.getMessage().contains("manifeste"), failure.getMessage());
    }

    @Test
    void plateformes() {
        assertEquals("darwin-arm64", VideoToolchainBundle.platformOf(OperatingSystem.MACOS, "aarch64"));
        assertEquals("darwin-x64", VideoToolchainBundle.platformOf(OperatingSystem.MACOS, "x86_64"));
        assertEquals("linux-x64", VideoToolchainBundle.platformOf(OperatingSystem.LINUX, "amd64"));
        assertEquals("win-x64", VideoToolchainBundle.platformOf(OperatingSystem.WINDOWS, "amd64"));
        assertNull(VideoToolchainBundle.platformOf(OperatingSystem.LINUX, "aarch64"), "pas de paquet linux-arm64");
        VideoToolchainBundle arm = bundle("jeton", "http://127.0.0.1:1/api", "aarch64");
        assertFalse(arm.supported());
        RenderFailure failure = assertThrows(RenderFailure.class, () -> arm.ensure(said::add));
        assertTrue(failure.remedy().contains("Node.js 22"), failure.remedy());
    }
}
