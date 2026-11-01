package fr.claudegateway.jarvis;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.shared.error.ErrorResponse;
import fr.claudegateway.speech.SpeechProviderException;
import fr.claudegateway.speech.SpeechProviderUnavailableException;
import jakarta.servlet.http.HttpServletResponse;

/**
 * <b>La voix de Jarvis</b> (F-231 / SF-231-01) : {@code POST /api/jarvis/speech} lit une phrase courte en
 * {@code audio/mpeg}, diffusée en flux ; {@code GET /api/jarvis/voice} dit si la voix est là et ce qu'elle a coûté
 * ce mois, en euros.
 *
 * <p>L'identité vient <b>exclusivement</b> du {@link CurrentUser} ; l'accès exige la Forge, comme la dictée
 * (F-145). La réponse audio n'est <b>engagée qu'au premier octet</b> : un fournisseur qui échoue avant rend une
 * erreur JSON lisible (502), jamais un audio vide en 200.</p>
 */
@RestController
@RequestMapping("/jarvis")
public class JarvisVoiceController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JarvisVoiceService voice;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisVoiceController(JarvisVoiceService voice, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.voice = voice;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @GetMapping("/voice")
    public JarvisVoiceService.VoiceStatus status() {
        atelierAccess.requireAccess();
        return voice.status(currentUser.requireId());
    }

    @PostMapping("/speech")
    public void speak(@RequestBody(required = false) SpeechRequest request, HttpServletResponse response)
            throws IOException {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        JarvisVoiceService.Reading reading;
        try {
            reading = voice.prepare(userId, request == null ? null : request.workspaceId(),
                    request == null ? null : request.text());
        } catch (IllegalArgumentException e) {
            error(response, HttpStatus.BAD_REQUEST, "validation_error", e.getMessage());
            return;
        } catch (SpeechProviderUnavailableException e) {
            error(response, HttpStatus.SERVICE_UNAVAILABLE, "speech_unavailable",
                    "La voix de Jarvis n'est pas disponible sur cette installation.");
            return;
        } catch (JarvisVoiceBudgetException e) {
            error(response, HttpStatus.TOO_MANY_REQUESTS, "jarvis_voice_budget",
                    "Le plafond mensuel de la voix de Jarvis est atteint : la réponse reste à l'écran.");
            return;
        }
        LazyAudioStream out = new LazyAudioStream(response);
        try {
            voice.speak(reading, out);
        } catch (SpeechProviderException | SpeechProviderUnavailableException e) {
            if (!out.started()) {
                // Le message du fournisseur n'est pas relayé : il peut porter des détails d'appel.
                error(response, HttpStatus.BAD_GATEWAY, "speech_failed", "La voix n'a pas répondu : réessayez.");
            }
            // Déjà engagée : le flux s'arrête là, l'écran garde le texte.
        }
    }

    private static void error(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(MAPPER.writeValueAsBytes(new ErrorResponse(code, message)));
    }

    /** Corps de {@code POST /api/jarvis/speech}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpeechRequest(String text, UUID workspaceId) {
    }

    /**
     * Une sortie qui n'engage la réponse ({@code 200 audio/mpeg}, sans cache) qu'au <b>premier octet</b> — jusque-là,
     * une erreur peut encore partir en JSON.
     */
    static final class LazyAudioStream extends OutputStream {

        private final HttpServletResponse response;
        private OutputStream target;

        LazyAudioStream(HttpServletResponse response) {
            this.response = response;
        }

        boolean started() {
            return target != null;
        }

        private OutputStream target() throws IOException {
            if (target == null) {
                response.setStatus(HttpStatus.OK.value());
                response.setContentType("audio/mpeg");
                response.setHeader("Cache-Control", "no-store");
                target = response.getOutputStream();
            }
            return target;
        }

        @Override
        public void write(int b) throws IOException {
            target().write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len > 0) {
                target().write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            if (target != null) {
                target.flush();
            }
        }
    }
}
