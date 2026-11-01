package fr.claudegateway.speech.elevenlabs;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.speech.SpeechProvider;
import fr.claudegateway.speech.SpeechProviderException;
import fr.claudegateway.speech.SpeechProviderUnavailableException;

/**
 * <b>La voix ElevenLabs</b> derrière {@link SpeechProvider} (F-233 / SF-233-06, D7, D-PO-1 b) — voix candidate de
 * Jarvis (Daniel, George, voix décrite…), en flux.
 *
 * <p>{@code POST {base-url}/v1/text-to-speech/{voice_id}/stream?output_format=mp3_44100_128} (en-tête
 * {@code xi-api-key}), corps {@code {text, model_id, language_code}} ; réponse = mp3 diffusé. La {@code voice} est un
 * <b>identifiant de voix ElevenLabs</b> (alphanumérique) ; les consignes de ton ne s'appliquent pas (ignorées).
 * <b>Ni le texte ni la clé ne sont journalisés.</b> Sans clé : {@link SpeechProviderUnavailableException}, aucun
 * appel.</p>
 *
 * <p>Ce n'est <b>pas</b> le fournisseur par défaut de la passerelle (la voix off F-215 reste sur
 * {@code OpenAiSpeechProvider}, {@code @Primary}) : seul Jarvis le choisit, par configuration.</p>
 */
@Component
public class ElevenLabsSpeechProvider implements SpeechProvider {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsSpeechProvider.class);

    public static final String NAME = "elevenlabs";
    /** Modèle par défaut : Flash v2.5 (≈ 75 ms annoncés, français). */
    public static final String DEFAULT_MODEL = "eleven_flash_v2_5";
    /** Un identifiant de voix ElevenLabs. */
    private static final Pattern VOICE_ID = Pattern.compile("^[A-Za-z0-9]{8,64}$");

    private final ElevenLabsProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public ElevenLabsSpeechProvider(ElevenLabsProperties properties, ObjectMapper objectMapper,
            RestClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, properties.timeout().toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        this.restClient = builder.requestFactory(factory).build();
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Vrai si la clé est fournie. */
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** Vrai si {@code voice} a la forme d'un identifiant de voix ElevenLabs. */
    public static boolean isVoiceId(String voice) {
        return voice != null && VOICE_ID.matcher(voice).matches();
    }

    @Override
    public SpeechAudio synthesize(String text, String voice, String instructions) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        synthesizeStream(text, voice, instructions, out);
        return new SpeechAudio(out.toByteArray(), "audio/mpeg");
    }

    @Override
    public long synthesizeStream(String text, String voice, String instructions, OutputStream out) {
        if (!isConfigured()) {
            throw new SpeechProviderUnavailableException("La voix ElevenLabs n'est pas configurée.");
        }
        if (text == null || text.isBlank()) {
            throw new SpeechProviderException("Texte vide : rien à lire.");
        }
        if (!isVoiceId(voice)) {
            throw new SpeechProviderUnavailableException("Identifiant de voix ElevenLabs illisible.");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", text);
        body.put("model_id", properties.ttsModel());
        body.put("language_code", "fr");
        try {
            Long written = restClient.post()
                    .uri(properties.baseUrl() + "/v1/text-to-speech/" + voice + "/stream?output_format=mp3_44100_128")
                    .header("xi-api-key", properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.parseMediaType("audio/mpeg"))
                    .body(body)
                    .exchange((request, resp) -> {
                        if (resp.getStatusCode().isError()) {
                            throw new SpeechProviderException(
                                    "Le fournisseur de voix a répondu une erreur (" + resp.getStatusCode().value() + ").");
                        }
                        long total = 0;
                        byte[] buffer = new byte[8_192];
                        try (InputStream in = resp.getBody()) {
                            int read;
                            while ((read = in.read(buffer)) >= 0) {
                                out.write(buffer, 0, read);
                                out.flush();
                                total += read;
                            }
                        }
                        return total;
                    });
            if (written == null || written == 0L) {
                throw new SpeechProviderException("Réponse vide du fournisseur de voix.");
            }
            return written;
        } catch (SpeechProviderException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("Appel à la voix ElevenLabs en échec (modèle={})", properties.ttsModel());
            throw new SpeechProviderException("Échec de l'appel au fournisseur de voix.", e);
        }
    }
}
