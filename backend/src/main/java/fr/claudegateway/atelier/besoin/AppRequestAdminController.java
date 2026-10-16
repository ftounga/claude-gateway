package fr.claudegateway.atelier.besoin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le registre des besoins, côté administration</b> (F-205 / SF-205-01) — l'onglet Besoins et Claude
 * Code ({@code GET /api/admin/app-requests?status=EN_ATTENTE}).
 *
 * <p>La garde est la <b>définition unique</b> ({@link AdminService#assertAdmin()}) : 403 pour un
 * non-administrateur. L'isolation {@code user_id} s'applique <b>en plus</b> : l'administrateur relit
 * <b>ses</b> besoins (même règle que le journal des ruptures, F-161).</p>
 */
@RestController
@RequestMapping("/admin/app-requests")
public class AppRequestAdminController {

    /** Types montrés tels quels dans le navigateur ; tout le reste se télécharge. */
    private static final Set<String> INLINE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp",
            "video/mp4", "video/webm", "video/quicktime", "application/pdf", "text/plain", "text/markdown",
            "text/csv", "application/json");

    private final AppRequestService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public AppRequestAdminController(AppRequestService service, AdminService adminService,
            CurrentUser currentUser) {
        this.service = service;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    /** Corps du {@code PATCH} : champ absent = inchangé ; {@code featureRef} vide = effacée. */
    public record UpdateRequest(String status, String featureRef) {
    }

    @GetMapping
    public List<AppRequestView> list(@RequestParam(required = false) String status) {
        adminService.assertAdmin();
        return service.list(currentUser.requireId(), status);
    }

    @GetMapping("/{requestId}")
    public AppRequestView detail(@PathVariable UUID requestId) {
        adminService.assertAdmin();
        return service.detail(currentUser.requireId(), requestId);
    }

    @PatchMapping("/{requestId}")
    public AppRequestView update(@PathVariable UUID requestId, @RequestBody UpdateRequest body) {
        adminService.assertAdmin();
        return service.update(currentUser.requireId(), requestId, body == null ? null : body.status(),
                body == null ? null : body.featureRef());
    }

    @GetMapping("/{requestId}/attachments/{position}")
    public ResponseEntity<byte[]> attachment(@PathVariable UUID requestId, @PathVariable int position) {
        adminService.assertAdmin();
        AppRequestService.AttachmentContent content = service.attachment(currentUser.requireId(), requestId,
                position);
        boolean inline = INLINE_TYPES.contains(content.contentType());
        MediaType type = inline ? MediaType.parseMediaType(content.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(content.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }
}
