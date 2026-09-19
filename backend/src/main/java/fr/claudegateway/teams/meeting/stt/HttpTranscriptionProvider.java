package fr.claudegateway.teams.meeting.stt;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * <b>Implémentation HTTP compatible Whisper</b> du {@link TranscriptionProvider} (F-128 / SF-128-04).
 *
 * <p>Relais Provider-First : {@code POST {base-url}/audio/transcriptions} en {@code multipart/form-data}
 * (champ {@code file} + {@code model} + {@code response_format=verbose_json}), à la façon de l'API
 * OpenAI/Whisper. <b>Base URL et clé configurables, rien en dur</b> ({@link TranscriptionProperties}).</p>
 *
 * <p><b>Éteint par défaut.</b> Si {@link TranscriptionProperties#isConfigured()} est faux, on lève
 * {@link TranscriptionProviderUnavailableException} <b>sans aucun appel réseau</b> — l'audio ne part pas.</p>
 */
@Component
public class HttpTranscriptionProvider implements TranscriptionProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpTranscriptionProvider.class);

    private final TranscriptionProperties properties;
    private final RestClient restClient;

    public HttpTranscriptionProvider(TranscriptionProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.requestFactory(requestFactory(properties)).build();
    }

    private static ClientHttpRequestFactory requestFactory(TranscriptionProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, properties.timeout().toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }

    @Override
    public Transcript transcribe(byte[] audio, String contentType, String languageHint) {
        if (!properties.isConfigured()) {
            throw new TranscriptionProviderUnavailableException(
                    "STT non configuré : aucun service de transcription n'est paramétré.");
        }
        if (audio == null || audio.length == 0) {
            throw new TranscriptionProviderException("Audio vide : rien à transcrire.");
        }
        if (audio.length > properties.maxAudioBytes()) {
            throw new TranscriptionProviderException("Audio trop volumineux pour le service STT ("
                    + audio.length + " octets, max " + properties.maxAudioBytes() + ").");
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedAudio(audio, filename(contentType)));
        body.add("model", properties.model());
        body.add("response_format", "verbose_json");
        String language = languageHint != null && !languageHint.isBlank()
                ? languageHint : properties.language();
        if (language != null && !language.isBlank()) {
            body.add("language", language);
        }

        try {
            WhisperResponse response = restClient.post()
                    .uri(properties.baseUrl().stripTrailing().replaceAll("/+$", "") + "/audio/transcriptions")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, resp) -> {
                        throw new TranscriptionProviderException(
                                "Le service STT a répondu une erreur (" + resp.getStatusCode().value() + ").");
                    })
                    .body(WhisperResponse.class);
            if (response == null) {
                throw new TranscriptionProviderException("Réponse vide du service STT.");
            }
            return new Transcript(render(response), response.language());
        } catch (TranscriptionProviderException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("Appel au service STT en échec (modèle={})", properties.model());
            throw new TranscriptionProviderException("Échec de l'appel au service de transcription.", e);
        }
    }

    /** Construit un texte horodaté depuis les segments ({@code [mm:ss] texte}), sinon le texte brut. */
    private static String render(WhisperResponse response) {
        List<WhisperSegment> segments = response.segments();
        if (segments == null || segments.isEmpty()) {
            return response.text() == null ? "" : response.text().strip();
        }
        StringBuilder out = new StringBuilder();
        for (WhisperSegment segment : segments) {
            String text = segment.text() == null ? "" : segment.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            out.append('[').append(timestamp(segment.start())).append("] ").append(text).append('\n');
        }
        return out.length() == 0 && response.text() != null ? response.text().strip() : out.toString().strip();
    }

    private static String timestamp(Double seconds) {
        long total = seconds == null ? 0 : Math.max(0, Math.round(seconds));
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    private static String filename(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("ogg")) {
            return "audio.ogg";
        }
        if (type.contains("mp4") || type.contains("m4a")) {
            return "audio.m4a";
        }
        if (type.contains("mpeg") || type.contains("mp3")) {
            return "audio.mp3";
        }
        if (type.contains("wav")) {
            return "audio.wav";
        }
        return "audio.webm";
    }

    /** Une partie fichier nommée : le service a besoin d'un nom de fichier pour deviner le format. */
    private static final class NamedAudio extends ByteArrayResource {
        private final String filename;

        private NamedAudio(byte[] content, String filename) {
            super(content);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WhisperResponse(String text, String language, List<WhisperSegment> segments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WhisperSegment(Double start, Double end, String text) {
    }
}
