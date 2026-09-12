package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
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

    // ---------------------------------------------------------------------------------------------
    // F-45 / SF-45-04 - le 407 : la seule reponse qui ne vient pas de la gateway.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("un 407 fait echouer le controle : c'est le proxy qui repond, pas la gateway")
    void aProxyAuthChallengeFailsThePreflight() throws IOException {
        String base = startServer(407, "Proxy Authentication Required");

        String message = preflight(OperatingSystem.WINDOWS).check(base);

        assertNotNull(message, "un 407 ne doit pas passer pour une gateway joignable");
        assertTrue(message.contains("407"), message);
        assertTrue(message.contains("proxy"), message);
        // D1 : dire QUI refuse. Sans cela, on cherche la panne du mauvais cote.
        assertTrue(message.contains("Ce n'est pas la gateway qui repond"), message);
    }

    @Test
    @DisplayName("le message du 407 nomme la limite de la JVM et ses deux issues")
    void theProxyAuthMessageNamesTheJvmLimitAndBothWaysOut() throws IOException {
        String base = startServer(407, "nope");

        String message = preflight(OperatingSystem.WINDOWS).check(base);

        // D3 : aucune version du runner ne corrigera cela - la cause est dans la JVM.
        assertTrue(message.contains("NTLM"), message);
        assertTrue(message.contains("Kerberos"), message);
        assertTrue(message.contains("SSPI"), message);
        assertTrue(message.contains("8u111"), message);
        assertTrue(message.contains("quelle que soit sa version"), message);
        // D4 : deux issues, parce qu'une exclusion de domaine se refuse.
        assertTrue(message.contains("DSI"), message);
        assertTrue(message.contains("cntlm"), message);
        assertTrue(message.contains("127.0.0.1:3128"), message);
        // Les gestes du systeme courant, jamais ceux d'un autre.
        assertTrue(message.contains("$env:HTTPS_PROXY"), message);
        // Aucun identifiant : le message ne recopie jamais une URL porteuse de « user:mot@hote ».
        assertFalse(message.contains("@"), message);
    }

    @Test
    @DisplayName("un 401 reste joignable : c'est la gateway qui parle, pas le proxy")
    void anUnauthorizedGatewayIsStillReachable() throws IOException {
        // Non-regression de D1 (SF-38-25) : seul le 407 fait exception, parce que seul le 407 vient
        // d'un intermediaire qui n'a rien transmis.
        assertNull(preflight().check(startServer(401, "nope")));
    }

    @Test
    @DisplayName("un tunnel CONNECT refuse est reconnu comme un 407, sans reponse a inspecter")
    void aRefusedTunnelIsRecognisedAsProxyAuth() {
        // D2 : sur une cible en HTTPS, le proxy refuse le CONNECT et la JVM leve une IOException -
        // il n'existe JAMAIS de HttpResponse. C'est la forme reellement rencontree chez le client.
        NetworkPreflight preflight = new NetworkPreflight(
                new ThrowingHttpClient(new IOException("Tunnel failed, got: 407")),
                OperatingSystem.LINUX);

        String message = preflight.check("https://portal.exemple.fr/api");

        assertNotNull(message);
        assertTrue(message.contains("SSPI"), message);
        assertTrue(message.contains("export HTTPS_PROXY=http://127.0.0.1:3128"), message);
    }

    @Test
    @DisplayName("une panne sans signature 407 garde le message reseau existant")
    void anOrdinaryFailureKeepsItsOwnMessage() {
        NetworkPreflight preflight = new NetworkPreflight(
                new ThrowingHttpClient(new IOException("Connection reset")),
                OperatingSystem.LINUX);

        String message = preflight.check("https://portal.exemple.fr/api");

        assertTrue(message.contains("pas joignable"), message);
        assertFalse(message.contains("SSPI"), message);
    }

    // ------------------------------------------------------------------ F-80 / SF-80-01

    @Test
    @DisplayName("un echec de poignee de main TLS est qualifie comme tel")
    void aTlsHandshakeFailureIsQualified() {
        // C'est ce verdict qui autorise le runner a LIRE la chaine : une SSLException prouve que le
        // serveur a repondu et presente un certificat.
        NetworkPreflight preflight = new NetworkPreflight(
                new ThrowingHttpClient(new IOException(
                        new javax.net.ssl.SSLHandshakeException("PKIX path building failed"))),
                OperatingSystem.LINUX);

        NetworkPreflight.Verdict verdict = preflight.verify("https://portal.exemple.fr/api");

        assertTrue(verdict.unreachable(), "un echec TLS reste un echec");
        assertTrue(verdict.tlsFailure(), "il y a une chaine a lire et un emetteur a nommer");
        assertTrue(verdict.message().contains("pas joignable"), verdict.message());
    }

    @Test
    @DisplayName("les autres pannes ne declenchent aucune sonde")
    void otherFailuresNeverTriggerTheProbe() throws IOException {
        // Un DNS muet, un port ferme ou un 407 ne laissent RIEN a regarder : sonder y ferait perdre
        // un delai d'attente pour ne rien afficher.
        assertFalse(new NetworkPreflight(
                new ThrowingHttpClient(new java.net.UnknownHostException("portal.exemple.fr")),
                OperatingSystem.LINUX).verify("https://portal.exemple.fr/api").tlsFailure());
        assertFalse(new NetworkPreflight(
                new ThrowingHttpClient(new IOException("Tunnel failed, got: 407")),
                OperatingSystem.LINUX).verify("https://portal.exemple.fr/api").tlsFailure());
        assertFalse(preflight().verify(startServer(200, "{}")).tlsFailure());
    }

    @Test
    @DisplayName("un 407 porte par une SSLException reste un 407, jamais une interception")
    void aProxyAuthCarriedByAnSslExceptionStaysAProxyAuth() {
        // C'est le PROXY qui parle, pas le serveur : le nommer « intercepteur » enverrait chercher
        // un certificat la ou il faut une authentification.
        NetworkPreflight preflight = new NetworkPreflight(
                new ThrowingHttpClient(new javax.net.ssl.SSLException(
                        "Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 407\"")),
                OperatingSystem.LINUX);

        NetworkPreflight.Verdict verdict = preflight.verify("https://portal.exemple.fr/api");

        assertTrue(verdict.unreachable());
        assertFalse(verdict.tlsFailure(), verdict.message());
        assertTrue(verdict.message().contains("SSPI"), verdict.message());
    }

    @Test
    @DisplayName("check() reste le message seul, pour les appelants qui n'en veulent pas plus")
    void checkStillReturnsTheMessageAlone() throws IOException {
        assertNull(preflight().check(startServer(200, "{}")));
    }

    @Test
    @DisplayName("declarer un proxy connu : la syntaxe du systeme, jamais rien")
    void declaringAKnownProxyUsesTheShellOfTheHost() {
        assertTrue(OperatingSystem.WINDOWS.declareProxy("http://127.0.0.1:3128")
                .contains("$env:HTTPS_PROXY"));
        // L'invite de commandes coexiste avec PowerShell : la forme `set` y est la seule qui marche.
        assertTrue(OperatingSystem.WINDOWS.declareProxy("http://127.0.0.1:3128").contains("set "));
        assertEquals("export HTTPS_PROXY=http://x:1",
                OperatingSystem.MACOS.declareProxy("http://x:1"));
        assertEquals("export HTTPS_PROXY=http://x:1",
                OperatingSystem.LINUX.declareProxy("http://x:1"));
        assertFalse(OperatingSystem.OTHER.declareProxy("http://x:1").isBlank());
    }

    private NetworkPreflight preflight(OperatingSystem os) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        return new NetworkPreflight(client, os);
    }

    /**
     * Client HTTP qui echoue toujours de la meme facon. Seul moyen d'eprouver le chemin ou le proxy
     * refuse le tunnel : cette exception ne se provoque pas avec un serveur local, puisque justement
     * aucune reponse n'existe.
     */
    private static final class ThrowingHttpClient extends HttpClient {

        private final IOException failure;

        private ThrowingHttpClient(IOException failure) {
            this.failure = failure;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException {
            throw failure;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.failedFuture(failure);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(failure);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public WebSocket.Builder newWebSocketBuilder() {
            throw new UnsupportedOperationException();
        }
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
