package fr.claudegateway.jarvis.calendar;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * F-243 / SF-243-03 — un Google bouchonné sur un port fixe ({@value #PORT}) : {@code /token} (échange du code,
 * rafraîchissement), {@code /revoke}, {@code /calendar/v3/calendars/primary/events}. Chaque requête est gardée
 * (chemin, requête, corps) pour vérifier ce qui part chez Google.
 */
final class GoogleTestServer {

    static final int PORT = 18943;
    static final String BASE = "http://127.0.0.1:" + PORT;

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

    static synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", GoogleTestServer::handle);
        server.start();
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
        RECEIVED.add(new Received(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(), body,
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
        if (path.endsWith("/calendars/primary/events") && "POST".equals(ex.getRequestMethod())) {
            if (unauthorizedLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                reply(ex, 401, "{\"error\":{\"code\":401}}");
                return;
            }
            reply(ex, 200, "{\"id\":\"evt-123\",\"status\":\"confirmed\"}");
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
