package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contrôle de vol réseau (F-38 / SF-38-25).
 *
 * <p>Écrit après trois obstacles en une heure chez un client, dont le dernier — un poste qui ne
 * résout pas les noms publics — échouait au milieu de l'appairage, mêlant une cause réseau à une
 * opération métier.</p>
 */
class NetworkPreflightTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("une gateway qui répond est joignable")
    void aRespondingGatewayIsReachable() throws IOException {
        String base = startServer(200, "{}");

        assertNull(preflight().check(base), "un 200 doit passer le contrôle");
    }

    @Test
    @DisplayName("un 404 vaut joignable : le contrôle ne juge pas la santé")
    void aNotFoundStillMeansReachable() throws IOException {
        // D1 : le contrôle répond à une seule question — « ce terminal sort-il jusqu'à cette
        // adresse ? ». Juger le code en ferait un test de santé, qui bloquerait un runner
        // parfaitement fonctionnel le jour où un endpoint change.
        String base = startServer(404, "nope");

        assertNull(preflight().check(base));
    }

    @Test
    @DisplayName("une erreur serveur vaut aussi joignable")
    void aServerErrorStillMeansReachable() throws IOException {
        String base = startServer(500, "boom");

        assertNull(preflight().check(base));
    }

    @Test
    @DisplayName("une gateway injoignable produit un message complet")
    void anUnreachableGatewayIsExplained() {
        // Port fermé : le cas du poste qui ne sort pas.
        String message = preflight().check("http://127.0.0.1:1");

        assertNotNull(message, "l'échec doit être expliqué, pas silencieux");
        assertTrue(message.contains("pas joignable"), message);
        // La cause vient de Failures (SF-38-24) : jamais « null ».
        assertFalse(message.contains(": null"), message);
        // D3 : nommer le paradoxe du navigateur, qui est l'indice décisif.
        assertTrue(message.contains("navigateur"), message);
        // Et les gestes du système courant.
        assertTrue(message.contains("HTTPS_PROXY"), message);
    }

    @Test
    @DisplayName("chaque système reçoit ses propres commandes")
    void eachOperatingSystemGetsItsOwnCommands() {
        assertTrue(OperatingSystem.WINDOWS.proxyInstructions().contains("netsh winhttp show proxy"));
        assertTrue(OperatingSystem.WINDOWS.proxyInstructions().contains("PowerShell"));
        assertTrue(OperatingSystem.MACOS.proxyInstructions().contains("scutil --proxy"));
        assertTrue(OperatingSystem.LINUX.proxyInstructions().contains("env | grep -i proxy"));
        // Un système inconnu reçoit les variables que le runner lit réellement — jamais rien.
        assertTrue(OperatingSystem.OTHER.proxyInstructions().contains("HTTPS_PROXY"));
        assertFalse(OperatingSystem.OTHER.proxyInstructions().isBlank());
    }

    @Test
    @DisplayName("le système est reconnu depuis os.name")
    void theOperatingSystemIsDetected() {
        assertEquals(OperatingSystem.WINDOWS, OperatingSystem.from("Windows 11"));
        assertEquals(OperatingSystem.MACOS, OperatingSystem.from("Mac OS X"));
        assertEquals(OperatingSystem.LINUX, OperatingSystem.from("Linux"));
        assertEquals(OperatingSystem.OTHER, OperatingSystem.from("Plan 9"));
        assertEquals(OperatingSystem.OTHER, OperatingSystem.from(null));
    }

    @Test
    @DisplayName("le message Windows mène jusqu'au fichier PAC")
    void theWindowsMessageGoesAllTheWayToThePacFile() {
        // C'est là que s'arrêtait le diagnostic réel : le proxy n'était ni dans l'environnement ni
        // dans netsh, mais derrière un AutoConfigURL.
        String instructions = OperatingSystem.WINDOWS.proxyInstructions();

        assertTrue(instructions.contains("AutoConfigURL"), instructions);
        assertTrue(instructions.contains(".pac"), instructions);
    }

    private NetworkPreflight preflight() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        return new NetworkPreflight(client, OperatingSystem.LINUX);
    }

    /** Démarre un serveur local qui répond toujours le même code, et rend sa base d'URL. */
    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
