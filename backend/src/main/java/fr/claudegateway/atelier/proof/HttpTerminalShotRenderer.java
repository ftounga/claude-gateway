package fr.claudegateway.atelier.proof;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.diagrams.DiagramProperties;
import fr.claudegateway.diagrams.DiagramRejectedException;
import fr.claudegateway.diagrams.DiagramRendererUnavailableException;

/**
 * Appel de la route {@code /terminal-shot} du service {@code diagram-renderer} (F-212 / SF-212-02) —
 * même service, même adresse ({@code app.diagrams.base-url}) que les diagrammes : aucune configuration
 * nouvelle.
 */
@Component
public class HttpTerminalShotRenderer implements TerminalShotRenderer {

    /** Chromium démarre à chaque capture : plus long qu'un diagramme. */
    static final Duration MIN_TIMEOUT = Duration.ofSeconds(45);
    static final int MAX_SHOTS = 12;

    private final DiagramProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public HttpTerminalShotRenderer(DiagramProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public boolean isAvailable() {
        return !properties.getBaseUrl().isBlank();
    }

    @Override
    public List<Shot> shoot(JsonNode session) {
        if (!isAvailable()) {
            throw new DiagramRendererUnavailableException(
                    "Le rendu des captures n'est pas configuré sur cette installation.");
        }
        if (session == null || !session.path("commands").isArray() || session.path("commands").isEmpty()) {
            throw new DiagramRejectedException("Aucune commande à photographier.");
        }
        String base = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        Duration timeout = properties.getTimeout().compareTo(MIN_TIMEOUT) > 0 ? properties.getTimeout() : MIN_TIMEOUT;
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/terminal-shot"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(session.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new DiagramRendererUnavailableException("Le service de rendu n'a pas répondu : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiagramRendererUnavailableException("Capture interrompue.");
        }
        int status = response.statusCode();
        if (status == 400 || status == 413 || status == 422) {
            throw new DiagramRejectedException(reason(response.body()));
        }
        if (status != 200) {
            throw new DiagramRendererUnavailableException("Le service de rendu a répondu " + status + ".");
        }
        JsonNode json;
        try {
            json = mapper.readTree(new String(response.body(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new DiagramRendererUnavailableException("Le service de rendu a renvoyé une réponse illisible.");
        }
        List<Shot> shots = new ArrayList<>();
        for (JsonNode image : json.path("images")) {
            if (shots.size() >= MAX_SHOTS) {
                break;
            }
            byte[] png;
            try {
                png = Base64.getDecoder().decode(image.path("png").asText(""));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (png.length == 0 || png.length > properties.getMaxImageBytes()) {
                continue;
            }
            shots.add(new Shot(png, image.path("width").asInt(0), image.path("height").asInt(0)));
        }
        if (shots.isEmpty()) {
            throw new DiagramRendererUnavailableException("Le service de rendu n'a renvoyé aucune image.");
        }
        return List.copyOf(shots);
    }

    private String reason(byte[] body) {
        try {
            String error = mapper.readTree(new String(body, StandardCharsets.UTF_8)).path("error").asText("");
            return error.isBlank() ? "La capture n'a pas pu être produite." : error;
        } catch (IOException | RuntimeException e) {
            return "La capture n'a pas pu être produite.";
        }
    }
}
