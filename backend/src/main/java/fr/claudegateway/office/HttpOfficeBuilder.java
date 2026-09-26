package fr.claudegateway.office;

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
 * La construction du document par le <b>service du cluster</b> (F-129 / SF-129-07) — le même que
 * celui des diagrammes et des decks : une seule image à déployer, une seule NetworkPolicy à tenir.
 *
 * <p><b>Deux échecs, deux sens</b> : une description invalide se corrige (on rend la raison) ; un
 * service muet n'a rien à voir avec elle — l'agent peut alors retomber sur {@code python-docx} /
 * {@code openpyxl} <b>s'ils sont déjà présents</b> sur le poste.</p>
 */
@Component
public class HttpOfficeBuilder implements OfficeBuilder {

    /** Un document prend plus de temps qu'une image : la borne lui est propre, comme pour le deck. */
    static final Duration TIMEOUT = Duration.ofSeconds(90);
    /** Borne du fichier produit, alignée sur celle du service. */
    static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    private final DiagramProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Autowired
    public HttpOfficeBuilder(DiagramProperties properties, ObjectMapper mapper) {
        this(properties, mapper, HttpClient.newHttpClient());
    }

    HttpOfficeBuilder(DiagramProperties properties, ObjectMapper mapper, HttpClient httpClient) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    @Override
    public boolean isAvailable() {
        return !properties.getBaseUrl().isBlank();
    }

    @Override
    public byte[] build(OfficeFormat format, JsonNode spec) {
        if (!isAvailable()) {
            throw new OfficeBuilderUnavailableException(
                    "La construction de documents n'est pas configurée sur cette installation.");
        }
        if (spec == null || !spec.isObject() || !spec.path(format.contentField()).isArray()
                || spec.path(format.contentField()).isEmpty()) {
            throw new OfficeRejectedException("La description doit porter au moins un élément « "
                    + format.contentField() + " ».");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("format", format.extension());
        body.set("spec", spec);
        String url = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/document"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new OfficeBuilderUnavailableException(
                    "Le service de construction n'a pas répondu : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OfficeBuilderUnavailableException("Construction interrompue.");
        }
        int status = response.statusCode();
        if (status == 200) {
            byte[] file = response.body();
            if (file == null || file.length == 0) {
                throw new OfficeBuilderUnavailableException("Le service a renvoyé un fichier vide.");
            }
            if (file.length > MAX_FILE_BYTES) {
                throw new OfficeRejectedException("Document trop lourd : " + file.length + " octets.");
            }
            return file;
        }
        if (status == 400 || status == 413 || status == 422) {
            throw new OfficeRejectedException(reason(response.body()));
        }
        throw new OfficeBuilderUnavailableException("Le service a répondu " + status + ".");
    }

    private String reason(byte[] body) {
        if (body == null || body.length == 0) {
            return "Le document n'a pas pu être construit.";
        }
        try {
            JsonNode node = mapper.readTree(new String(body, StandardCharsets.UTF_8));
            String error = node.path("error").asText("");
            return error.isBlank() ? "Le document n'a pas pu être construit." : error;
        } catch (IOException e) {
            return "Le document n'a pas pu être construit.";
        }
    }
}
