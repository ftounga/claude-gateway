package fr.claudegateway.images;

import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>Implémentation HTTP compatible OpenAI</b> du {@link ImageProvider} (F-142 / SF-142-04).
 *
 * <p>Relais Provider-First : {@code POST {base-url}/images/generations} en JSON
 * ({@code {model, prompt, size, n:1}}), exactement la forme de l'API OpenAI
 * ({@code https://api.openai.com/v1}) : auth {@code Authorization: Bearer <clé>}, réponse
 * {@code {data:[{b64_json}]}}. {@code gpt-image-1} renvoie toujours du Base64 ; pour les modèles
 * {@code dall-e-*} on demande explicitement {@code response_format:"b64_json"} (voir
 * {@link #wantsResponseFormat(String)}). <b>Base URL, modèle et clé configurables, rien en dur</b>
 * ({@link ImageGenerationProperties}).</p>
 *
 * <p><b>Le prompt est une donnée</b> : il part dans le corps JSON, jamais concaténé à une instruction.</p>
 *
 * <p><b>Éteint par défaut.</b> Si {@link ImageGenerationProperties#isConfigured()} est faux, on lève
 * {@link ImageProviderUnavailableException} <b>sans aucun appel réseau</b> — rien ne part.</p>
 */
@Component
public class OpenAiImageProvider implements ImageProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageProvider.class);

    private final ImageGenerationProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OpenAiImageProvider(ImageGenerationProperties properties, RestClient.Builder builder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = builder.requestFactory(requestFactory(properties)).build();
    }

    private static ClientHttpRequestFactory requestFactory(ImageGenerationProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, properties.timeout().toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }

    @Override
    public GeneratedImageData generate(String prompt, ImageSize size) {
        if (!properties.isConfigured()) {
            throw new ImageProviderUnavailableException(
                    "Génération d'images non configurée : aucun fournisseur d'images n'est paramétré.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new ImageProviderException("Description vide : rien à générer.");
        }
        ImageSize effective = size == null ? ImageSize.SQUARE : size;

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.model());
        body.put("prompt", prompt);
        body.put("size", effective.api());
        body.put("n", 1);
        if (wantsResponseFormat(properties.model())) {
            body.put("response_format", "b64_json");
        }

        try {
            ImagesResponse response = restClient.post()
                    .uri(properties.baseUrl().stripTrailing().replaceAll("/+$", "") + "/images/generations")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, resp) -> {
                        throw new ImageProviderException(
                                "Le fournisseur d'images a répondu une erreur (" + resp.getStatusCode().value() + ").");
                    })
                    .body(ImagesResponse.class);
            byte[] bytes = decode(response);
            return new GeneratedImageData(bytes, "image/png");
        } catch (ImageProviderException e) {
            throw e;
        } catch (RestClientException e) {
            // On ne journalise NI le prompt NI la clé : juste le modèle, comme le STT.
            log.warn("Appel au fournisseur d'images en échec (modèle={})", properties.model());
            throw new ImageProviderException("Échec de l'appel au fournisseur d'images.", e);
        }
    }

    /**
     * {@code gpt-image-1} n'accepte pas le paramètre {@code response_format} (il renvoie toujours du
     * Base64) ; les modèles {@code dall-e-*}, si — on ne le demande donc que pour ceux-là.
     */
    private static boolean wantsResponseFormat(String model) {
        String name = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return !name.startsWith("gpt-image");
    }

    private static byte[] decode(ImagesResponse response) {
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new ImageProviderException("Réponse vide du fournisseur d'images.");
        }
        String b64 = response.data().get(0).b64Json();
        if (b64 == null || b64.isBlank()) {
            throw new ImageProviderException("Le fournisseur d'images n'a pas renvoyé d'image (Base64 absent).");
        }
        try {
            return Base64.getDecoder().decode(b64.strip());
        } catch (IllegalArgumentException e) {
            throw new ImageProviderException("Image illisible : Base64 du fournisseur invalide.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ImagesResponse(List<ImageDatum> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ImageDatum(
            @com.fasterxml.jackson.annotation.JsonProperty("b64_json") String b64Json) {
    }
}
