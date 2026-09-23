package fr.claudegateway.diagrams;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Le rendu par le <b>service du cluster</b> (F-142 / SF-142-06) : un appel HTTP interne, borné.
 *
 * <p><b>Ce qui sort du poste, et ce qui n'en sort pas</b> : le <b>code</b> du diagramme (du texte)
 * monte jusqu'ici pour être rendu — c'est le prix de « zéro installation chez le client », arbitré par
 * le PO le 2026-09-23. L'image redescend et est déposée dans le projet ; rien n'est conservé ici.</p>
 *
 * <p><b>Deux échecs, deux sens</b> : un code invalide est la faute du diagramme (on rend la raison du
 * moteur, qui permet de corriger) ; un service muet n'a rien à voir avec le code (on propose le repli
 * par la page). Les confondre ferait chercher l'erreur au mauvais endroit.</p>
 */
@Component
public class HttpDiagramRenderer implements DiagramRenderer {

    private final DiagramProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    /**
     * Le constructeur de Spring. <b>Annoté explicitement</b> : la classe en a deux (le second sert aux
     * tests, qui injectent leur propre client), et sans cette annotation Spring cherche un constructeur
     * par défaut — le contexte entier refuse alors de démarrer.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public HttpDiagramRenderer(DiagramProperties properties, ObjectMapper mapper) {
        this(properties, mapper, HttpClient.newHttpClient());
    }

    HttpDiagramRenderer(DiagramProperties properties, ObjectMapper mapper, HttpClient httpClient) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    @Override
    public boolean isAvailable() {
        return !properties.getBaseUrl().isBlank();
    }

    @Override
    public Rendered render(String code, Format format, Integer width) {
        if (!isAvailable()) {
            throw new DiagramRendererUnavailableException(
                    "Le rendu de diagrammes n'est pas configuré sur cette installation.");
        }
        String source = code == null ? "" : code.strip();
        if (source.isEmpty()) {
            throw new DiagramRejectedException("Aucun code de diagramme.");
        }
        if (source.length() > properties.getMaxCodeChars()) {
            throw new DiagramRejectedException("Diagramme trop long : " + source.length()
                    + " caractères pour un maximum de " + properties.getMaxCodeChars() + ".");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("code", source);
        body.put("format", format == Format.SVG ? "svg" : "png");
        body.put("width", width == null || width <= 0 ? properties.getDefaultWidth() : width);
        return call(body, format);
    }

    @Override
    public Rendered renderCloud(JsonNode spec) {
        if (!isAvailable()) {
            throw new DiagramRendererUnavailableException(
                    "Le rendu de diagrammes n'est pas configuré sur cette installation.");
        }
        if (spec == null || !spec.isObject() || !spec.path("nodes").isArray()
                || spec.path("nodes").isEmpty()) {
            throw new DiagramRejectedException("La description doit porter au moins un nœud "
                    + "(« nodes »), chacun avec son « id », son « type » et son « label ».");
        }
        String payload = spec.toString();
        if (payload.length() > properties.getMaxCodeChars()) {
            throw new DiagramRejectedException("Description trop longue : " + payload.length()
                    + " caractères pour un maximum de " + properties.getMaxCodeChars() + ".");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("engine", "cloud");
        body.set("spec", spec);
        return call(body, Format.PNG);
    }

    /** L'appel au service, partagé par les deux moteurs — une seule façon de lire une réponse. */
    private Rendered call(ObjectNode body, Format format) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/render"))
                .timeout(properties.getTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new DiagramRendererUnavailableException(
                    "Le service de rendu n'a pas répondu : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiagramRendererUnavailableException("Rendu interrompu.");
        }
        int status = response.statusCode();
        if (status == 200) {
            byte[] image = response.body();
            if (image == null || image.length == 0) {
                throw new DiagramRendererUnavailableException("Le service de rendu a renvoyé une image vide.");
            }
            if (image.length > properties.getMaxImageBytes()) {
                throw new DiagramRejectedException("Image rendue trop lourde : " + image.length
                        + " octets pour un maximum de " + properties.getMaxImageBytes() + ".");
            }
            // F-142 / SF-142-09 : les types rendus sans icône officielle voyagent en en-tête.
            String unknown = response.headers().firstValue("X-Cg-Unknown-Types").orElse("");
            return new Rendered(image, format, unknown);
        }
        if (status == 400 || status == 413 || status == 422) {
            throw new DiagramRejectedException(reason(response.body()));
        }
        throw new DiagramRendererUnavailableException(
                "Le service de rendu a répondu " + status + " : réessayez, ou rendez le diagramme en page.");
    }

    private String base() {
        String url = properties.getBaseUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** La raison donnée par le moteur, ou une phrase honnête si elle est illisible. */
    private String reason(byte[] body) {
        if (body == null || body.length == 0) {
            return "Le diagramme n'a pas pu être rendu.";
        }
        try {
            JsonNode node = mapper.readTree(new String(body, StandardCharsets.UTF_8));
            String error = node.path("error").asText("");
            return error.isBlank() ? "Le diagramme n'a pas pu être rendu." : error;
        } catch (IOException e) {
            return "Le diagramme n'a pas pu être rendu.";
        }
    }
}
