package fr.claudegateway.stt.stream;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.jarvis.stt.JarvisSttStreamProperties;
import fr.claudegateway.speech.elevenlabs.ElevenLabsProperties;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderException;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/**
 * <b>ElevenLabs Scribe v2 Realtime</b> derrière {@link StreamingTranscriptionProvider} (F-233 / SF-233-04, D-PO-1 b).
 *
 * <p>{@code POST {base-url}/v1/single-use-token/realtime_scribe} (en-tête {@code xi-api-key}) rend un jeton à usage
 * unique ; le navigateur ouvre ensuite {@code {ws-base-url}/v1/speech-to-text/realtime?…&token=…}. <b>Ni la clé ni le
 * jeton ne sont journalisés.</b> Sans clé : {@link TranscriptionProviderUnavailableException}, aucun appel.</p>
 */
@Component
public class ElevenLabsStreamingTranscriptionProvider implements StreamingTranscriptionProvider {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsStreamingTranscriptionProvider.class);

    public static final String NAME = "elevenlabs";
    static final int SAMPLE_RATE = 16_000;
    /** Durée de vie annoncée d'un jeton à usage unique ElevenLabs. */
    static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private final ElevenLabsProperties elevenLabs;
    private final JarvisSttStreamProperties properties;
    private final RestClient restClient;
    private final Clock clock;

    public ElevenLabsStreamingTranscriptionProvider(ElevenLabsProperties elevenLabs,
            JarvisSttStreamProperties properties, RestClient.Builder builder, Clock clock) {
        this.elevenLabs = elevenLabs;
        this.properties = properties;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, elevenLabs.timeout().toMillis());
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
    public StreamingSession openStreamingSession(String languageHint, double silenceSeconds,
            java.util.List<String> vocabulary) {
        if (!isConfigured()) {
            throw new TranscriptionProviderUnavailableException("L'écoute en flux n'est pas configurée.");
        }
        JsonNode body;
        try {
            body = restClient.post()
                    .uri(elevenLabs.baseUrl() + "/v1/single-use-token/realtime_scribe")
                    .header("xi-api-key", elevenLabs.apiKey())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, resp) -> {
                        throw new TranscriptionProviderException(
                                "Le fournisseur d'écoute a répondu une erreur (" + resp.getStatusCode().value() + ").");
                    })
                    .body(JsonNode.class);
        } catch (TranscriptionProviderException e) {
            log.warn("Jeton d'écoute en flux refusé par le fournisseur");
            throw e;
        } catch (RestClientException e) {
            log.warn("Appel au fournisseur d'écoute en flux en échec");
            throw new TranscriptionProviderException("Échec de l'appel au fournisseur d'écoute.", e);
        }
        String token = body == null ? null : body.path("token").asText(null);
        if (token == null || token.isBlank()) {
            throw new TranscriptionProviderException("Réponse du fournisseur d'écoute sans jeton.");
        }
        Instant expiresAt = clock.instant().plus(TOKEN_TTL);
        return new StreamingSession(NAME, sessionUrl(elevenLabs.wsBaseUrl(), properties.model(), languageHint,
                silenceSeconds, vocabulary), token, expiresAt, SAMPLE_RATE);
    }

    /** L'URL WebSocket de la session, sans le jeton. */
    static String sessionUrl(String wsBaseUrl, String model, String languageHint, double silenceSeconds) {
        return sessionUrl(wsBaseUrl, model, languageHint, silenceSeconds, java.util.List.of());
    }

    /** F-239 / SF-239-04 — l'URL, avec un paramètre {@code keyterms} par terme du vocabulaire (accepté en flux). */
    static String sessionUrl(String wsBaseUrl, String model, String languageHint, double silenceSeconds,
            java.util.List<String> vocabulary) {
        StringBuilder url = new StringBuilder(wsBaseUrl).append("/v1/speech-to-text/realtime?model_id=")
                .append(encode(model))
                .append("&audio_format=pcm_").append(SAMPLE_RATE)
                .append("&commit_strategy=vad")
                .append("&vad_silence_threshold_secs=")
                .append(String.format(Locale.ROOT, "%.2f", Math.max(0.3, Math.min(3.0, silenceSeconds))));
        if (languageHint != null && !languageHint.isBlank()) {
            url.append("&language_code=").append(encode(languageHint.strip().toLowerCase(Locale.ROOT)));
        }
        for (String term : vocabulary == null ? java.util.List.<String>of() : vocabulary) {
            url.append("&keyterms=").append(encode(term));
        }
        return url.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
