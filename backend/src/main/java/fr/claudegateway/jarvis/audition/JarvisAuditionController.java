package fr.claudegateway.jarvis.audition;

import java.util.NoSuchElementException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.jarvis.JarvisVoiceBudgetException;
import fr.claudegateway.shared.error.ErrorResponse;
import fr.claudegateway.speech.SpeechProviderException;
import fr.claudegateway.speech.SpeechProviderUnavailableException;

/**
 * <b>L'audition à l'aveugle de la voix de Jarvis</b> (F-233 / SF-233-07) — <b>administrateur uniquement</b>
 * ({@link AdminService#assertAdmin()}, 403 sinon), sur ses propres données ({@code user_id} du {@link CurrentUser}).
 */
@RestController
@RequestMapping("/admin/jarvis/audition")
public class JarvisAuditionController {

    private final JarvisAuditionService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public JarvisAuditionController(JarvisAuditionService service, AdminService adminService,
            CurrentUser currentUser) {
        this.service = service;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public JarvisAuditionService.Audition audition() {
        adminService.assertAdmin();
        return service.audition(currentUser.requireId());
    }

    @PostMapping("/{label}/sample")
    public ResponseEntity<?> sample(@PathVariable String label, @RequestBody(required = false) SampleRequest body) {
        adminService.assertAdmin();
        try {
            byte[] audio = service.sample(currentUser.requireId(), label, body == null ? null : body.phrase());
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg"))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store").body(audio);
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    @PostMapping("/{label}/choose")
    public ResponseEntity<?> choose(@PathVariable String label) {
        adminService.assertAdmin();
        try {
            return ResponseEntity.ok(service.choose(currentUser.requireId(), label));
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    @DeleteMapping("/choice")
    public ResponseEntity<Void> reset() {
        adminService.assertAdmin();
        service.reset(currentUser.requireId());
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<?> error(RuntimeException e) {
        if (e instanceof NoSuchElementException) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("not_found", "Voix inconnue."));
        }
        if (e instanceof IllegalArgumentException) {
            return ResponseEntity.badRequest().body(new ErrorResponse("validation_error", e.getMessage()));
        }
        if (e instanceof SpeechProviderUnavailableException) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse("speech_unavailable",
                    "Cette voix n'est pas disponible sur cette installation."));
        }
        if (e instanceof JarvisVoiceBudgetException) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorResponse("jarvis_voice_budget",
                    "Le plafond mensuel de la voix de Jarvis est atteint."));
        }
        if (e instanceof SpeechProviderException) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse("speech_failed",
                    "La voix n'a pas répondu : réessayez."));
        }
        throw e;
    }

    /** Corps de l'échantillon : l'index de la phrase. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SampleRequest(Integer phrase) {
    }
}
