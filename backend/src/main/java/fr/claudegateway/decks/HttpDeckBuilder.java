package fr.claudegateway.decks;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.diagrams.DiagramProperties;

/**
 * La construction du deck par le <b>service du cluster</b> (F-129 / SF-129-05) — le même que celui des
 * diagrammes : une seule image à déployer, une seule NetworkPolicy à tenir.
 *
 * <p><b>Deux échecs, deux sens</b> : une description invalide se corrige (on rend la raison) ; un
 * service muet n'a rien à voir avec elle — l'agent peut alors retomber sur {@code python-pptx} <b>s'il
 * est présent</b> sur le poste.</p>
 */
@Component
public class HttpDeckBuilder implements DeckBuilder {

    /** Une présentation prend plus de temps qu'une image : la borne lui est propre. */
    static final Duration TIMEOUT = Duration.ofSeconds(90);
    /** Borne du fichier produit, alignée sur celle du service. */
    static final int MAX_DECK_BYTES = 8 * 1024 * 1024;

    private final DiagramProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Autowired
    public HttpDeckBuilder(DiagramProperties properties, ObjectMapper mapper) {
        this(properties, mapper, HttpClient.newHttpClient());
    }

    HttpDeckBuilder(DiagramProperties properties, ObjectMapper mapper, HttpClient httpClient) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    @Override
    public boolean isAvailable() {
        return !properties.getBaseUrl().isBlank();
    }

    @Override
    public Deck build(JsonNode spec) {
        if (!isAvailable()) {
            throw new DeckBuilderUnavailableException(
                    "La construction de présentations n'est pas configurée sur cette installation.");
        }
        if (spec == null || !spec.isObject() || !spec.path("slides").isArray()
                || spec.path("slides").isEmpty()) {
            throw new DeckRejectedException("La description doit porter au moins une slide « slides ».");
        }
        ObjectNode body = mapper.createObjectNode();
        body.set("spec", spec);
        String url = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/presentation"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new DeckBuilderUnavailableException(
                    "Le service de construction n'a pas répondu : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeckBuilderUnavailableException("Construction interrompue.");
        }
        int status = response.statusCode();
        if (status == 200) {
            byte[] file = response.body();
            if (file == null || file.length == 0) {
                throw new DeckBuilderUnavailableException("Le service a renvoyé un fichier vide.");
            }
            if (file.length > MAX_DECK_BYTES) {
                throw new DeckRejectedException("Présentation trop lourde : " + file.length + " octets.");
            }
            return new Deck(file);
        }
        if (status == 400 || status == 413 || status == 422) {
            throw new DeckRejectedException(reason(response.body()));
        }
        throw new DeckBuilderUnavailableException("Le service a répondu " + status + ".");
    }

    private String reason(byte[] body) {
        if (body == null || body.length == 0) {
            return "La présentation n'a pas pu être construite.";
        }
        try {
            JsonNode node = mapper.readTree(new String(body, StandardCharsets.UTF_8));
            String error = node.path("error").asText("");
            return error.isBlank() ? "La présentation n'a pas pu être construite." : error;
        } catch (IOException e) {
            return "La présentation n'a pas pu être construite.";
        }
    }
}
