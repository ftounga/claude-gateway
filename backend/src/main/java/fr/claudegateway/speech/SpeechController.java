package fr.claudegateway.speech;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * <b>Paramètres › Vidéos</b> (F-215 / SF-215-01) : les voix du fournisseur, la voix par défaut du compte et
 * l'échantillon fixe de chaque voix. Tout est scellé par le {@code user_id} du jeton.
 */
@RestController
@RequestMapping("/speech")
public class SpeechController {

    private final SpeechProperties properties;
    private final SpeechPreferenceService preferences;
    private final SpeechSampleService samples;
    private final CurrentUser currentUser;

    public SpeechController(SpeechProperties properties, SpeechPreferenceService preferences,
            SpeechSampleService samples, CurrentUser currentUser) {
        this.properties = properties;
        this.preferences = preferences;
        this.samples = samples;
        this.currentUser = currentUser;
    }

    /** Les voix, les réglages par défaut (D2) et la préférence du compte. */
    @GetMapping("/voices")
    public VoicesView voices() {
        return view(currentUser.requireId());
    }

    /** Enregistre la voix par défaut ; 400 lisible si la voix est inconnue ou les consignes trop longues. */
    @PutMapping("/preference")
    public ResponseEntity<?> save(@RequestBody PreferenceRequest request) {
        UUID userId = currentUser.requireId();
        try {
            preferences.save(userId, request == null ? null : request.voice(),
                    request == null ? null : request.instructions());
            return ResponseEntity.ok(view(userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("validation_error", e.getMessage()));
        }
    }

    /** Revient aux réglages par défaut (D2). */
    @DeleteMapping("/preference")
    public ResponseEntity<Void> reset() {
        preferences.reset(currentUser.requireId());
        return ResponseEntity.noContent().build();
    }

    /** L'échantillon fixe d'une voix (mp3) ; 404 voix inconnue, 503 voix éteinte, 502 fournisseur en échec. */
    @GetMapping("/voices/{voice}/sample")
    public ResponseEntity<?> sample(@PathVariable String voice) {
        UUID userId = currentUser.requireId();
        if (!properties.allows(voice)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("not_found", "Voix inconnue."));
        }
        try {
            byte[] bytes = samples.sample(userId, voice);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .cacheControl(CacheControl.noStore())
                    .body(bytes);
        } catch (SpeechProviderUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("speech_unavailable", "La voix off n'est pas disponible sur cette instance."));
        } catch (SpeechProviderException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ErrorResponse("speech_failed", "Le fournisseur de voix n'a pas répondu : réessayez."));
        }
    }

    private VoicesView view(UUID userId) {
        List<Voice> voices = properties.voices().stream().map(v -> new Voice(v, SpeechVoices.label(v))).toList();
        Preference preference = preferences.find(userId)
                .map(p -> new Preference(p.getVoice(), p.getInstructions()))
                .orElse(null);
        return new VoicesView(properties.isActive(), voices, SpeechVoices.DEFAULT_VOICE,
                SpeechVoices.DEFAULT_INSTRUCTIONS, preference);
    }

    /** Corps de {@code PUT /speech/preference}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreferenceRequest(String voice, String instructions) {
    }

    /** Une voix et son libellé. */
    public record Voice(String id, String label) {
    }

    /** La voix par défaut du compte. */
    public record Preference(String voice, String instructions) {
    }

    /** Ce que l'écran Paramètres › Vidéos affiche. */
    public record VoicesView(boolean enabled, List<Voice> voices, String defaultVoice, String defaultInstructions,
            Preference preference) {
    }
}
