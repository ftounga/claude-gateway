package fr.claudegateway.jarvis.stt;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.jarvis.JarvisVoiceBudgetException;
import fr.claudegateway.shared.error.ErrorResponse;
import fr.claudegateway.teams.meeting.stt.TranscriptionProperties;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderException;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/**
 * <b>La retranscription de l'énoncé de Jarvis</b> (F-239 / SF-239-04) : {@code POST /api/jarvis/utterance}
 * (multipart {@code audio}, {@code seconds} facultatif) ⇒ {@code { text, provider, ms }}. Identité exclusivement par
 * {@link CurrentUser} ; accès Forge requis, comme la voix. Rien n'est conservé ni journalisé.
 */
@RestController
public class JarvisUtteranceController {

    private final JarvisUtteranceService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;
    private final TranscriptionProperties limits;

    public JarvisUtteranceController(JarvisUtteranceService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser, TranscriptionProperties limits) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
        this.limits = limits;
    }

    @PostMapping("/jarvis/utterance")
    public ResponseEntity<?> transcribe(@RequestParam(value = "audio", required = false) MultipartFile audio,
            @RequestParam(value = "seconds", required = false) Double seconds) throws IOException {
        atelierAccess.requireAccess();
        if (audio == null || audio.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("validation_error", "Aucun énoncé reçu."));
        }
        if (audio.getSize() > limits.maxAudioBytes()) {
            // Refusé AVANT de lire les octets.
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new ErrorResponse("too_large", "Énoncé trop long pour être retranscrit."));
        }
        try {
            return ResponseEntity.ok(service.transcribe(currentUser.requireId(), audio.getBytes(),
                    audio.getContentType(), seconds));
        } catch (TranscriptionProviderUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(
                    "utterance_unavailable", "La retranscription n'est pas disponible sur cette installation."));
        } catch (JarvisVoiceBudgetException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorResponse(
                    "jarvis_voice_budget", "Le plafond mensuel de la voix de Jarvis est atteint."));
        } catch (TranscriptionProviderException e) {
            // Le message du fournisseur n'est pas relayé : il peut porter des détails d'appel.
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(
                    "utterance_failed", "La retranscription a échoué."));
        }
    }
}
