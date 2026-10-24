package fr.claudegateway.speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Implémentation HTTP compatible OpenAI</b> du {@link SpeechProvider} (F-215 / SF-215-01).
 *
 * <p>{@code POST {base-url}/audio/speech} en JSON ({@code {model, input, voice, instructions,
 * response_format:"mp3"}}), auth {@code Authorization: Bearer <clé>}, réponse = les octets mp3. Base URL,
 * modèle et clé configurables ({@link SpeechProperties}). <b>Ni le texte ni la clé ne sont journalisés.</b></p>
 *
 * <p><b>Éteint par défaut sans clé</b> : {@link SpeechProviderUnavailableException} sans appel réseau.</p>
 */
@Component
public class OpenAiSpeechProvider implements SpeechProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSpeechProvider.class);

    private final SpeechProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OpenAiSpeechProvider(SpeechProperties properties, RestClient.Builder builder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = builder.requestFactory(requestFactory(properties)).build();
    }

    private static ClientHttpRequestFactory requestFactory(SpeechProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, properties.timeout().toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }

    @Override
    public SpeechAudio synthesize(String text, String voice, String instructions) {
        if (!properties.isActive()) {
            throw new SpeechProviderUnavailableException(
                    "Voix off non configurée : aucun fournisseur de synthèse vocale n'est paramétré.");
        }
        if (text == null || text.isBlank()) {
            throw new SpeechProviderException("Texte vide : rien à lire.");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.model());
        body.put("input", text);
        body.put("voice", voice);
        if (instructions != null && !instructions.isBlank()) {
            body.put("instructions", instructions);
        }
        body.put("response_format", "mp3");
        try {
            byte[] bytes = restClient.post()
                    .uri(properties.baseUrl().strip().replaceAll("/+$", "") + "/audio/speech")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, resp) -> {
                        throw new SpeechProviderException(
                                "Le fournisseur de voix a répondu une erreur (" + resp.getStatusCode().value() + ").");
                    })
                    .body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new SpeechProviderException("Réponse vide du fournisseur de voix.");
            }
            return new SpeechAudio(bytes, "audio/mpeg");
        } catch (SpeechProviderException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("Appel au fournisseur de voix en échec (modèle={})", properties.model());
            throw new SpeechProviderException("Échec de l'appel au fournisseur de voix.", e);
        }
    }
}
