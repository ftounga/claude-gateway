package fr.claudegateway.jarvis.calendar;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * F-243 — un serveur HTTP local qui joue l'adresse iCal secrète de Google : 200 avec {@code ETag}, 304 sur
 * {@code If-None-Match} égal, 404 (adresse réinitialisée), ou un corps qui n'est pas un agenda. Une route par chemin.
 */
final class CalendarTestServer {

    /** Ce que rend une route. */
    static final class Route {
        volatile int status = 200;
        volatile String body = "";
        volatile String etag;
        final AtomicInteger hits = new AtomicInteger();
        final AtomicInteger conditional = new AtomicInteger();
    }

    private static HttpServer server;
    private static final Map<String, Route> ROUTES = new ConcurrentHashMap<>();

    private CalendarTestServer() {
    }

    static synchronized int port() {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", exchange -> {
                Route route = ROUTES.get(exchange.getRequestURI().getRawPath());
                if (route == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                route.hits.incrementAndGet();
                String inm = exchange.getRequestHeaders().getFirst("If-None-Match");
                if (inm != null) {
                    route.conditional.incrementAndGet();
                }
                if (route.status == 200 && route.etag != null && route.etag.equals(inm)) {
                    exchange.sendResponseHeaders(304, -1);
                    exchange.close();
                    return;
                }
                if (route.status != 200) {
                    exchange.sendResponseHeaders(route.status, -1);
                    exchange.close();
                    return;
                }
                byte[] bytes = route.body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/calendar; charset=UTF-8");
                if (route.etag != null) {
                    exchange.getResponseHeaders().add("ETag", route.etag);
                }
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        }
        return server.getAddress().getPort();
    }

    /** Une nouvelle route ; rend son adresse complète. */
    static String route(String path, Route route) {
        ROUTES.put(path, route);
        return "http://127.0.0.1:" + port() + path;
    }
}
