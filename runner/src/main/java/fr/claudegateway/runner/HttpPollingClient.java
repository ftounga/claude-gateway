package fr.claudegateway.runner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Client HTTP du repli long-polling (F-38 / SF-38-09). Réutilise l'{@link HttpClient} du runner —
 * donc le proxy d'entreprise et le truststore déjà résolus (SF-38-03) : c'est tout l'intérêt du
 * repli, emprunter le chemin HTTP que le proxy laisse passer.
 *
 * <p>Le jeton voyage dans l'en-tête {@code X-Runner-Token}, jamais en query : une query finit dans
 * les journaux d'accès du proxy et de l'ingress. Il n'est écrit nulle part ailleurs.</p>
 */
public final class HttpPollingClient implements PollingTransport {

    /** Marge ajoutée au délai serveur avant d'abandonner la requête côté client. */
    private static final Duration READ_MARGIN = Duration.ofSeconds(20);

    private final HttpClient httpClient;
    private final RunnerConfig config;
    private final String token;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpPollingClient(HttpClient httpClient, RunnerConfig config, String token) {
        this.httpClient = httpClient;
        this.config = config;
        this.token = token;
    }

    @Override
    public List<String> poll(long waitMs) throws IOException {
        HttpRequest request = base(config.pollUrl() + "?waitMs=" + waitMs)
                .timeout(Duration.ofMillis(waitMs).plus(READ_MARGIN))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = exchange(request);
        int status = response.statusCode();
        if (status == 401) {
            throw new RunnerConnection.AuthRejectedException("Jeton refusé par la gateway (401)");
        }
        if (status == 409) {
            throw new ChannelClosedException("Liaison fermée par la gateway (409)");
        }
        if (status < 200 || status >= 300) {
            throw new IOException("Réponse HTTP " + status + " sur le long-poll");
        }
        return framesOf(response.body());
    }

    @Override
    public void send(List<String> frames) throws IOException {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        HttpRequest request = base(config.sendUrl())
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(frames)))
                .build();
        HttpResponse<String> response = exchange(request);
        int status = response.statusCode();
        if (status == 401) {
            throw new RunnerConnection.AuthRejectedException("Jeton refusé par la gateway (401)");
        }
        if (status < 200 || status >= 300) {
            throw new IOException("Réponse HTTP " + status + " sur le dépôt de trames");
        }
    }

    @Override
    public void disconnect() {
        try {
            httpClient.send(base(config.disconnectUrl())
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (IOException | RuntimeException e) {
            // Arrêt propre « best effort » : la gateway fermera de toute façon le canal inactif.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpRequest.Builder base(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header(RunnerHeaders.TOKEN, token);
    }

    private HttpResponse<String> exchange(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Échange interrompu");
        }
    }

    /** Corps du dépôt : {@code {"frames":[…]}}, les trames étant réinsérées <b>verbatim</b>. */
    private String body(List<String> frames) {
        return "{\"frames\":[" + String.join(",", frames) + "]}";
    }

    /** Trames rendues par le poll, ré-sérialisées à l'identique pour l'aiguilleur de trames. */
    private List<String> framesOf(String payload) {
        List<String> frames = new ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return frames;
        }
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (Exception e) {
            return frames; // réponse illisible : traitée comme un poll vide, jamais une erreur fatale
        }
        JsonNode array = root == null ? null : root.get("frames");
        if (array == null || !array.isArray()) {
            return frames;
        }
        for (JsonNode frame : array) {
            if (frame.isObject()) {
                frames.add(frame.toString());
            }
        }
        return frames;
    }
}
