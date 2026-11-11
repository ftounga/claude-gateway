package fr.claudegateway.jarvis.stt;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.jarvis.JarvisVoiceBudgetException;
import fr.claudegateway.shared.error.ErrorResponse;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderException;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderUnavailableException;

/**
 * <b>L'écoute en flux de Jarvis</b> (F-233 / SF-233-04) : {@code POST /api/jarvis/stt-session} émet une session
 * (jeton éphémère du fournisseur) ; {@code POST /api/jarvis/stt-session/{id}/close} la clôt et la compte.
 * Identité exclusivement par {@link CurrentUser} ; accès Forge requis, comme la voix.
 */
@RestController
public class JarvisSttSessionController {

    private final JarvisSttSessionService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisSttSessionController(JarvisSttSessionService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @PostMapping("/jarvis/stt-session")
    public ResponseEntity<?> open(@RequestBody(required = false) OpenRequest body) {
        atelierAccess.requireAccess();
        try {
            // F-257 / SF-257-04 — {"mode":"long"} : la session longue du service vocal (commit manuel).
            boolean longSession = body != null && "long".equalsIgnoreCase(body.mode());
            UUID userId = currentUser.requireId();
            return ResponseEntity.ok(longSession ? service.openLong(userId) : service.open(userId));
        } catch (TranscriptionProviderUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(
                    "stt_stream_unavailable", "L'écoute en flux n'est pas disponible sur cette installation."));
        } catch (JarvisVoiceBudgetException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorResponse(
                    "jarvis_voice_budget", "Le plafond mensuel de la voix de Jarvis est atteint."));
        } catch (TranscriptionProviderException e) {
            // Le message du fournisseur n'est pas relayé : il peut porter des détails d'appel.
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(
                    "stt_stream_failed", "L'écoute en flux n'a pas pu s'ouvrir : réessayez."));
        }
    }

    @PostMapping("/jarvis/stt-session/{id}/close")
    public ResponseEntity<?> close(@PathVariable UUID id, @RequestBody(required = false) CloseRequest body) {
        atelierAccess.requireAccess();
        try {
            service.close(currentUser.requireId(), id, body == null ? null : body.seconds());
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("not_found",
                    "Session inconnue."));
        }
    }

    /** F-257 / SF-257-04 — corps facultatif de l'ouverture : {@code mode} = {@code long} pour le service vocal. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenRequest(String mode) {
    }

    /** Corps de la clôture : la durée de session vue par l'écran, en secondes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CloseRequest(Long seconds) {
    }
}
