package fr.claudegateway.jarvis.voicestream;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * <b>La session vocale en flux</b> (F-257 / SF-257-02) : {@code POST /api/jarvis/voice-session} émet le ticket à
 * l'écran ; {@code GET /api/jarvis/voice-session/me} le décrit au service {@code jarvis-voice} (ticket seulement).
 * Identité exclusivement par {@link CurrentUser} ; accès Forge requis, comme la voix.
 */
@RestController
public class JarvisVoiceSessionController {

    private static final String BEARER = "Bearer ";

    private final JarvisVoiceSessionService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisVoiceSessionController(JarvisVoiceSessionService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @PostMapping("/jarvis/voice-session")
    public ResponseEntity<?> issue(@RequestBody(required = false) IssueRequest body) {
        atelierAccess.requireAccess();
        try {
            return ResponseEntity.ok(service.issue(currentUser.requireId(), body == null ? null : body.workspaceId()));
        } catch (JarvisVoiceSessionService.VoiceStreamDisabledException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(
                    "voice_stream_disabled", "La conversation en flux n'est pas activée sur cette installation."));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("workspace_not_found",
                    "Sujet inconnu."));
        }
    }

    @GetMapping("/jarvis/voice-session/me")
    public ResponseEntity<?> me(@RequestHeader(name = "Authorization", required = false) String authorization) {
        UUID userId = currentUser.requireId();
        String bearer = authorization != null && authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()).trim() : null;
        JarvisVoiceSessionService.VoiceIdentity identity = service.identify(bearer);
        if (identity == null || !userId.equals(identity.userId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("voice_ticket_required",
                    "Cette route n'accepte qu'un ticket de session vocale."));
        }
        return ResponseEntity.ok(identity);
    }

    /** Corps de l'émission : le sujet de la conversation (facultatif). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IssueRequest(UUID workspaceId) {
    }
}
