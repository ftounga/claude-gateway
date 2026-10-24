package fr.claudegateway.atelier.video;

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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.diagrams.DiagramProperties;
import fr.claudegateway.diagrams.DiagramRejectedException;
import fr.claudegateway.diagrams.DiagramRendererUnavailableException;

/**
 * Appel de la route {@code /icons} du service {@code diagram-renderer} (F-215 / SF-215-03) — même service, même
 * adresse ({@code app.diagrams.base-url}) que les diagrammes : aucune configuration nouvelle.
 */
@Component
public class HttpVideoIconSource implements VideoIconSource {

    static final int MAX_NAMES = 40;
    static final int MAX_ICON_BYTES = 512 * 1024;

    private final DiagramProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public HttpVideoIconSource(DiagramProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public boolean isAvailable() {
        return !properties.getBaseUrl().isBlank();
    }

    @Override
    public List<String> search(String query) {
        ObjectNode body = mapper.createObjectNode().put("chercher", query == null ? "" : query);
        JsonNode json = call(body);
        List<String> names = new ArrayList<>();
        json.path("trouvees").forEach(node -> {
            if (names.size() < MAX_NAMES && node.isTextual()) {
                names.add(node.asText());
            }
        });
        return List.copyOf(names);
    }

    @Override
    public Batch fetch(List<String> names) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode list = body.putArray("noms");
        names.stream().limit(MAX_NAMES).forEach(list::add);
        JsonNode json = call(body);
        List<Icon> icons = new ArrayList<>();
        List<String> missing = new ArrayList<>(texts(json.path("manquantes")));
        for (JsonNode icon : json.path("icones")) {
            String name = icon.path("nom").asText("");
            byte[] png;
            try {
                png = Base64.getDecoder().decode(icon.path("png").asText(""));
            } catch (IllegalArgumentException e) {
                missing.add(name);
                continue;
            }
            if (name.isBlank() || png.length == 0 || png.length > MAX_ICON_BYTES) {
                missing.add(name);
                continue;
            }
            icons.add(new Icon(name, png));
        }
        return new Batch(List.copyOf(icons), List.copyOf(missing), texts(json.path("invalides")));
    }

    private JsonNode call(ObjectNode body) {
        if (!isAvailable()) {
            throw new DiagramRendererUnavailableException("Les icônes ne sont pas configurées sur cette installation.");
        }
        String base = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/icons"))
                .timeout(properties.getTimeout() == null ? Duration.ofSeconds(30) : properties.getTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new DiagramRendererUnavailableException("Le service d'icônes n'a pas répondu.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiagramRendererUnavailableException("Demande d'icônes interrompue.");
        }
        int status = response.statusCode();
        if (status == 400 || status == 413) {
            throw new DiagramRejectedException(reason(response.body()));
        }
        if (status != 200) {
            throw new DiagramRendererUnavailableException("Le service d'icônes a répondu " + status + ".");
        }
        try {
            return mapper.readTree(new String(response.body(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new DiagramRendererUnavailableException("Le service d'icônes a renvoyé une réponse illisible.");
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> {
            if (node.isTextual()) {
                out.add(node.asText());
            }
        });
        return List.copyOf(out);
    }

    private String reason(byte[] body) {
        try {
            String error = mapper.readTree(new String(body, StandardCharsets.UTF_8)).path("error").asText("");
            return error.isBlank() ? "Demande d'icônes refusée." : error;
        } catch (IOException | RuntimeException e) {
            return "Demande d'icônes refusée.";
        }
    }
}
