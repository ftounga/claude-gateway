package fr.claudegateway.stt.utterance;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.jarvis.stt.JarvisUtteranceProperties;
import fr.claudegateway.speech.elevenlabs.ElevenLabsProperties;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderException;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/**
 * <b>ElevenLabs Scribe v2 (batch)</b> derrière {@link UtteranceTranscriptionProvider} (F-239 / SF-239-04).
 *
 * <p>{@code POST {base-url}/v1/speech-to-text} (multipart, en-tête {@code xi-api-key}) : {@code model_id},
 * {@code language_code}, {@code file}, et un champ {@code keyterms} par terme du vocabulaire (le fournisseur accepte
 * jusqu'à 1 000 termes de moins de 50 caractères et 5 mots ; au-delà de 100, il facture 20 s minimum par requête —
 * l'appelant borne à 100). <b>Ni la clé, ni l'audio, ni le texte ne sont journalisés.</b></p>
 */
@Component
public class ElevenLabsUtteranceTranscriptionProvider implements UtteranceTranscriptionProvider {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsUtteranceTranscriptionProvider.class);

    public static final String NAME = "elevenlabs";

    private final ElevenLabsProperties elevenLabs;
    private final JarvisUtteranceProperties properties;
    private final RestClient restClient;

    public ElevenLabsUtteranceTranscriptionProvider(ElevenLabsProperties elevenLabs,
            JarvisUtteranceProperties properties, RestClient.Builder builder) {
        this.elevenLabs = elevenLabs;
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, properties.providerTimeout().toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        this.restClient = builder.requestFactory(factory).build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        return elevenLabs.isConfigured();
    }

    @Override
    public String transcribe(byte[] audio, String contentType, String languageHint, List<String> vocabulary) {
        if (!isConfigured()) {
            throw new TranscriptionProviderUnavailableException("La retranscription n'est pas configurée.");
        }
        JsonNode body;
        try {
            body = restClient.post()
                    .uri(elevenLabs.baseUrl() + "/v1/speech-to-text")
                    .header("xi-api-key", elevenLabs.apiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form(audio, contentType, languageHint, vocabulary, properties.model()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, resp) -> {
                        throw new TranscriptionProviderException(
                                "Le fournisseur de retranscription a répondu une erreur ("
                                        + resp.getStatusCode().value() + ").");
                    })
                    .body(JsonNode.class);
        } catch (TranscriptionProviderException e) {
            log.warn("Retranscription refusée par le fournisseur");
            throw e;
        } catch (RestClientException e) {
            log.warn("Appel au fournisseur de retranscription en échec");
            throw new TranscriptionProviderException("Échec de l'appel au fournisseur de retranscription.", e);
        }
        return body == null ? "" : body.path("text").asText("").strip();
    }

    /** Le corps multipart de l'appel (exposé aux tests). */
    static MultiValueMap<String, Object> form(byte[] audio, String contentType, String languageHint,
            List<String> vocabulary, String model) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("model_id", model);
        if (languageHint != null && !languageHint.isBlank()) {
            form.add("language_code", languageHint.strip().toLowerCase(Locale.ROOT));
        }
        form.add("tag_audio_events", "false");
        String filename = contentType != null && contentType.contains("webm") ? "enonce.webm"
                : contentType != null && contentType.contains("ogg") ? "enonce.ogg" : "enonce.wav";
        form.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        for (String term : vocabulary == null ? List.<String>of() : vocabulary) {
            form.add("keyterms", term);
        }
        return form;
    }
}
