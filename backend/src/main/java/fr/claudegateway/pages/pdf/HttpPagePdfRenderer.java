package fr.claudegateway.pages.pdf;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.diagrams.DiagramProperties;

/**
 * Le moteur PDF par le <b>service de rendu du cluster</b> (F-184 / SF-184-02) : {@code POST /pdf}, borné.
 *
 * <p>Même service, même adresse que le rendu de diagrammes ({@code app.diagrams.base-url}) : vide, la
 * fonctionnalité est éteinte. Deux échecs, deux sens — comme pour les diagrammes : un refus du moteur est
 * dit avec sa raison (422), un moteur muet n'a rien à voir avec la page (503).</p>
 */
@Component
public class HttpPagePdfRenderer implements PagePdfRenderer {

    private final DiagramProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final Duration timeout;

    /** Le constructeur de Spring — annoté : le second sert aux tests, qui injectent leur client. */
    @Autowired
    public HttpPagePdfRenderer(DiagramProperties properties, ObjectMapper mapper,
            @Value("${app.pages.pdf.timeout:PT60S}") Duration timeout) {
        this(properties, mapper, HttpClient.newHttpClient(), timeout);
    }

    HttpPagePdfRenderer(DiagramProperties properties, ObjectMapper mapper, HttpClient httpClient, Duration timeout) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofSeconds(60) : timeout;
    }

    @Override
    public boolean isAvailable() {
        return !properties.getBaseUrl().isBlank();
    }

    @Override
    public Printed print(String html, List<Resource> resources) {
        if (!isAvailable()) {
            throw new PagePdfUnavailableException("Le PDF n'est pas disponible sur cette installation.");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("html", html);
        ArrayNode list = body.putArray("resources");
        Base64.Encoder base64 = Base64.getEncoder();
        for (Resource resource : resources) {
            list.addObject()
                    .put("url", resource.url())
                    .put("contentType", resource.contentType())
                    .put("body", base64.encodeToString(resource.body()));
        }
        HttpResponse<byte[]> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/pdf"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new PagePdfUnavailableException("Le PDF n'a pas pu être produit pour le moment, réessayez.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PagePdfUnavailableException("Le PDF n'a pas pu être produit pour le moment, réessayez.");
        }
        int status = response.statusCode();
        if (status == 200) {
            return new Printed(response.body(), response.headers().firstValue("X-Cg-Missing-Resources").orElse(""));
        }
        if (status == 400 || status == 413 || status == 422) {
            throw new PagePdfRejectedException(reason(response.body()));
        }
        throw new PagePdfUnavailableException("Le PDF n'a pas pu être produit pour le moment, réessayez.");
    }

    private String reason(byte[] body) {
        try {
            JsonNode json = mapper.readTree(new String(body, StandardCharsets.UTF_8));
            String error = json.path("error").asText("");
            if (!error.isBlank()) {
                return error.length() > 500 ? error.substring(0, 500) : error;
            }
        } catch (IOException | RuntimeException e) {
            // Corps illisible : raison générique ci-dessous.
        }
        return "La page n'a pas pu être imprimée en PDF.";
    }
}
