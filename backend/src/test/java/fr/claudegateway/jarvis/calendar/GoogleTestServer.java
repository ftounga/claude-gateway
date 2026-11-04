package fr.claudegateway.jarvis.calendar;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.context.DynamicPropertyRegistry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * F-243 / SF-243-03, SF-243-04 — un Google bouchonné (un par JVM de test, port dynamique) : {@code /token} (échange,
 * rafraîchissement), {@code /revoke}, la création d'un événement, et la réponse à une invitation (recherche par
 * {@code iCalUID}, {@code PATCH}). Chaque requête est gardée pour vérifier ce qui part chez Google.
 */
final class GoogleTestServer {

    /** Une requête reçue. */
    record Received(String method, String path, String query, String body, String authorization) {
    }

    static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    static volatile String scope = "openid email https://www.googleapis.com/auth/calendar.events";
    static volatile boolean refreshRevoked = false;
    /** Nombre de 401 à rendre avant d'accepter une création (rafraîchissement attendu). */
    static final AtomicInteger unauthorizedLeft = new AtomicInteger();
    static final AtomicInteger accessSerial = new AtomicInteger();

    private static HttpServer server;

    private GoogleTestServer() {
    }

    static synchronized String base() {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", GoogleTestServer::handle);
            server.start();
        }
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Le client OAuth de test, pointé sur ce Google bouchonné. */
    static void register(DynamicPropertyRegistry registry) {
        String base = base();
        registry.add("app.jarvis.calendar.oauth.client-id", () -> "test-client");
        registry.add("app.jarvis.calendar.oauth.client-secret", () -> "test-secret");
        registry.add("app.jarvis.calendar.oauth.redirect-uri",
                () -> "http://localhost/api/jarvis/calendar/oauth/callback");
        registry.add("app.jarvis.calendar.oauth.auth-uri", () -> base + "/auth");
        registry.add("app.jarvis.calendar.oauth.token-uri", () -> base + "/token");
        registry.add("app.jarvis.calendar.oauth.revoke-uri", () -> base + "/revoke");
        registry.add("app.jarvis.calendar.oauth.api-base", () -> base + "/calendar/v3");
    }

    static void reset() {
        RECEIVED.clear();
        scope = "openid email https://www.googleapis.com/auth/calendar.events";
        refreshRevoked = false;
        unauthorizedLeft.set(0);
    }

    private static void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        RECEIVED.add(new Received(method, path, ex.getRequestURI().getRawQuery(), body,
                ex.getRequestHeaders().getFirst("Authorization")));
        if ("/token".equals(path) && body.contains("grant_type=authorization_code")) {
            if (!body.contains("code=good-code")) {
                reply(ex, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            String claims = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"email\":\"Pro@NG-ITConsulting.com\"}".getBytes(StandardCharsets.UTF_8));
            reply(ex, 200, "{\"access_token\":\"access-" + accessSerial.incrementAndGet()
                    + "\",\"expires_in\":3600,\"refresh_token\":\"refresh-secret-xyz\",\"scope\":\"" + scope
                    + "\",\"id_token\":\"e30." + claims + ".sig\"}");
            return;
        }
        if ("/token".equals(path) && body.contains("grant_type=refresh_token")) {
            if (refreshRevoked) {
                reply(ex, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            reply(ex, 200, "{\"access_token\":\"access-" + accessSerial.incrementAndGet() + "\",\"expires_in\":3600}");
            return;
        }
        if ("/revoke".equals(path)) {
            reply(ex, 200, "{}");
            return;
        }
        if (path.endsWith("/calendars/primary/events") && "POST".equals(method)) {
            if (unauthorizedLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                reply(ex, 401, "{\"error\":{\"code\":401}}");
                return;
            }
            reply(ex, 200, "{\"id\":\"evt-123\",\"status\":\"confirmed\"}");
            return;
        }
        if (path.endsWith("/calendars/primary/events") && "GET".equals(method)) {
            reply(ex, 200, "{\"items\":[{\"id\":\"evt-inv\",\"attendees\":["
                    + "{\"email\":\"devis@helios.example\",\"organizer\":true,\"responseStatus\":\"accepted\"},"
                    + "{\"email\":\"pro@ng-itconsulting.com\",\"self\":true,\"responseStatus\":\"needsAction\"}]}]}");
            return;
        }
        if (path.endsWith("/calendars/primary/events/evt-inv") && "PATCH".equals(method)) {
            reply(ex, 200, "{\"id\":\"evt-inv\"}");
            return;
        }
        reply(ex, 404, "{}");
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
