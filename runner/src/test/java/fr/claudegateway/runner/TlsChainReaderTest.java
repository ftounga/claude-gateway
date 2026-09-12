package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-80 / SF-80-01 — <b>le garde-fou</b> de la lecture de diagnostic.
 *
 * <p>Cette lecture accepte n'importe quel certificat. C'est ce qui lui permet de lire ce que la JVM
 * vient de refuser — et c'est exactement pourquoi elle doit être tenue par des tests, et non par un
 * commentaire. Deux propriétés, et elles ne sont pas négociables :</p>
 *
 * <ol>
 *   <li><b>aucun octet de trafic</b> ne passe par elle : le serveur qu'elle contacte ne reçoit
 *       qu'une poignée de main, jamais une requête ;</li>
 *   <li>elle <b>ne relâche jamais</b> la vérification du canal réel : aucun réglage global de la
 *       JVM n'est touché, donc rien d'autre dans ce processus ne peut hériter de cette confiance.</li>
 * </ol>
 */
class TlsChainReaderTest {

    /** Au-delà, le test bloque au lieu d'échouer. */
    private static final int WAIT_SECONDS = 10;

    @Test
    @DisplayName("la lecture n'emet AUCUN octet applicatif : une poignee de main, et rien d'autre")
    void theDiagnosticReadNeverSendsApplicationBytes() throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>(new byte[0]);
        CountDownLatch captured = new CountDownLatch(1);

        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                // Serveur EN CLAIR : il n'achèvera aucune poignée de main. Peu importe — ce qu'on
                // mesure est ce que le client a ÉCRIT avant de renoncer.
                try (Socket client = listener.accept(); InputStream in = client.getInputStream()) {
                    client.setSoTimeout(2_000);
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    // Une seule lecture : le ClientHello tient dans un enregistrement. On coupe
                    // ensuite, pour que le client renonce tout de suite au lieu d'attendre 10 s.
                    int read = in.read(chunk);
                    if (read > 0) {
                        buffer.write(chunk, 0, read);
                    }
                    received.set(buffer.toByteArray());
                } catch (Exception expected) {
                    // Une poignée de main qui n'aboutit pas se termine par une coupure : normal.
                } finally {
                    captured.countDown();
                }
            }, "diagnostic-capture");
            server.setDaemon(true);
            server.start();

            URI target = URI.create("https://127.0.0.1:" + listener.getLocalPort()
                    + "/api/runner/download/formats");
            // La lecture échoue (le pair ne parle pas TLS) : c'est attendu, et l'appelant se tait.
            assertThrows(Exception.class,
                    () -> TlsChainReader.readWithoutValidating(target, null));

            assertTrue(captured.await(WAIT_SECONDS, TimeUnit.SECONDS), "le serveur n'a rien capté");
        }

        byte[] bytes = received.get();
        assertTrue(bytes.length > 0, "le client doit au moins avoir tenté une poignée de main");
        // 0x16 = enregistrement TLS « handshake ». Le premier octet écrit sur le fil est donc du
        // protocole, pas de l'applicatif.
        assertTrue((bytes[0] & 0xFF) == 0x16,
                "le premier octet doit être un enregistrement de poignée de main TLS, reçu 0x"
                        + Integer.toHexString(bytes[0] & 0xFF));

        String asText = new String(bytes, StandardCharsets.ISO_8859_1);
        // Le défaut que ce test garde : `HttpsURLConnection` — ce qu'utilisait la sonde d'origine —
        // ENVOIE une requête pour obtenir la chaîne. Plus aucune ne doit partir d'ici.
        assertFalse(asText.contains("GET "), "aucune requête HTTP ne doit partir : " + asText);
        assertFalse(asText.contains("HTTP/1.1"), asText);
        assertFalse(asText.contains("runner/download/formats"), asText);
        assertFalse(asText.contains("Authorization"), asText);
    }

    @Test
    @DisplayName("la lecture ne touche AUCUN reglage global : le canal reel reste verifie")
    void theDiagnosticReadNeverRelaxesAnythingGlobal() throws Exception {
        SSLContext defaultContextBefore = SSLContext.getDefault();
        SSLSocketFactory httpsFactoryBefore = HttpsURLConnection.getDefaultSSLSocketFactory();

        int closedPort;
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = listener.getLocalPort();
        }
        URI target = URI.create("https://127.0.0.1:" + closedPort + "/api");
        assertThrows(Exception.class, () -> TlsChainReader.readWithoutValidating(target, null));

        assertSame(defaultContextBefore, SSLContext.getDefault(),
                "le contexte par défaut de la JVM ne doit jamais devenir permissif");
        assertSame(httpsFactoryBefore, HttpsURLConnection.getDefaultSSLSocketFactory(),
                "HttpsURLConnection ne doit jamais hériter de la confiance de diagnostic");

        // Et la confiance ordinaire de la JVM reste peuplée, là où celle du diagnostic n'annonce
        // aucun émetteur : les deux ne sont pas la même chose, et ne le deviennent pas.
        TrustManagerFactory jvmTrust =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        jvmTrust.init((KeyStore) null);
        X509TrustManager ordinary = (X509TrustManager) jvmTrust.getTrustManagers()[0];
        assertTrue(ordinary.getAcceptedIssuers().length > 0,
                "le magasin de la JVM reste celui qui décide pour le trafic");
        assertTrue(TlsChainReader.DiagnosticTrust.INSTANCE.getAcceptedIssuers().length == 0,
                "la confiance de diagnostic n'est utilisable pour aucune décision");
    }

    @Test
    @DisplayName("la confiance de diagnostic n'accepte aucun emetteur : elle ne SERT pas a valider")
    void theDiagnosticTrustDeclaresNoIssuer() {
        // `getAcceptedIssuers()` vide : un `TrustManager` qui n'annonce aucun émetteur ne peut être
        // utilisé pour bâtir une décision de confiance. Il ne sait que LIRE.
        assertTrue(TlsChainReader.DiagnosticTrust.INSTANCE.getAcceptedIssuers().length == 0);
    }

    @Test
    @DisplayName("une cible sans hote ne fait rien du tout")
    void aTargetWithoutHostDoesNothing() throws Exception {
        assertTrue(TlsChainReader.readWithoutValidating(URI.create("https:///api"), null).isEmpty());
    }

    @Test
    @DisplayName("un proxy qui refuse le tunnel fait echouer la lecture, sans poignee de main")
    void aRefusedTunnelFailsTheRead() throws Exception {
        try (ServerSocket relay = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread proxy = new Thread(() -> {
                try (Socket client = relay.accept()) {
                    client.setSoTimeout(2_000);
                    client.getInputStream().read(new byte[512]);
                    client.getOutputStream().write(
                            "HTTP/1.1 403 Forbidden\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                } catch (Exception ignored) {
                    // Le test porte sur le client.
                }
            }, "refusing-proxy");
            proxy.setDaemon(true);
            proxy.start();

            java.net.ProxySelector selector = fixedProxy(relay.getLocalPort());
            URI target = URI.create("https://portal.exemple.fr/api/runner/download/formats");

            assertThrows(Exception.class,
                    () -> TlsChainReader.readWithoutValidating(target, selector));
        }
    }

    /** Sélecteur qui renvoie toujours le même proxy HTTP local. */
    private static java.net.ProxySelector fixedProxy(int port) {
        return new java.net.ProxySelector() {
            @Override
            public java.util.List<java.net.Proxy> select(URI uri) {
                return java.util.List.of(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), port)));
            }

            @Override
            public void connectFailed(URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {
                // Sans objet pour ce test.
            }
        };
    }
}
