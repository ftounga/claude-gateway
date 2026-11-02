package fr.claudegateway.jarvis.capture;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.shared.error.ErrorResponse;
import fr.claudegateway.teams.meeting.stt.TranscriptionProperties;

/**
 * <b>La capture réelle des énoncés</b> (F-239 / SF-239-03) : {@code POST /api/jarvis/captures} (gardée seulement
 * pour un ADMIN qui l'a demandé, sinon 204 sans rien garder) ; {@code GET /api/admin/jarvis/captures} et
 * {@code GET /api/admin/jarvis/captures/{id}/audio} (ADMIN, ses propres captures). Identité par {@link CurrentUser}.
 */
@RestController
public class JarvisCaptureController {

    private final JarvisCaptureService service;
    private final AtelierAccessService atelierAccess;
    private final AdminService adminService;
    private final CurrentUser currentUser;
    private final TranscriptionProperties limits;

    public JarvisCaptureController(JarvisCaptureService service, AtelierAccessService atelierAccess,
            AdminService adminService, CurrentUser currentUser, TranscriptionProperties limits) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.adminService = adminService;
        this.currentUser = currentUser;
        this.limits = limits;
    }

    @PostMapping("/jarvis/captures")
    public ResponseEntity<?> record(@RequestParam(value = "audio", required = false) MultipartFile audio,
            @RequestParam(value = "seconds", required = false) Double seconds,
            @RequestParam(value = "listenMode", required = false) String listenMode,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "streamText", required = false) String streamText,
            @RequestParam(value = "finalText", required = false) String finalText) throws IOException {
        atelierAccess.requireAccess();
        if (audio == null || audio.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("validation_error", "Aucun énoncé reçu."));
        }
        if (audio.getSize() > limits.maxAudioBytes()) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new ErrorResponse("too_large", "Énoncé trop long."));
        }
        service.record(currentUser.requireId(), adminService.isAdmin(), audio.getBytes(),
                new JarvisCaptureService.Incoming(seconds, listenMode, provider, streamText, finalText));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/jarvis/captures")
    public List<JarvisCaptureService.CaptureView> list() {
        adminService.assertAdmin();
        return service.list(currentUser.requireId());
    }

    @GetMapping("/admin/jarvis/captures/{id}/audio")
    public ResponseEntity<byte[]> audio(@PathVariable UUID id) {
        adminService.assertAdmin();
        return service.audio(currentUser.requireId(), id)
                .map(bytes -> ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
