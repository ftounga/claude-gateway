package fr.claudegateway.export;

import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.export.dto.AnswerExportRequest;
import jakarta.validation.Valid;

/**
 * Endpoints d'export (F-14) : téléchargement d'une conversation ou d'une réponse documentée en
 * Markdown/PDF. L'identité provient exclusivement du {@link CurrentUser} (JWT) ; l'isolation
 * {@code user_id} est appliquée dans le service, jamais depuis un paramètre client. Aucune logique
 * métier ici (orchestration dans {@link ExportService}).
 */
@RestController
public class ExportController {

    private final ExportService exportService;
    private final CurrentUser currentUser;

    public ExportController(ExportService exportService, CurrentUser currentUser) {
        this.exportService = exportService;
        this.currentUser = currentUser;
    }

    /** Exporte la conversation possédée par l'utilisateur courant. */
    @GetMapping("/conversations/{id}/export")
    public ResponseEntity<byte[]> exportConversation(
            @PathVariable UUID id,
            @RequestParam(name = "format", required = false) String format) {
        UUID userId = currentUser.requireId();
        ExportedFile file = exportService.exportConversation(userId, id, ExportFormat.fromParam(format));
        return toResponse(file);
    }

    /** Exporte une réponse documentée fournie par l'appelant (stateless ; auth requise). */
    @PostMapping("/export/answer")
    public ResponseEntity<byte[]> exportAnswer(
            @RequestParam(name = "format", required = false) String format,
            @Valid @RequestBody AnswerExportRequest request) {
        currentUser.requireId();
        ExportedFile file = exportService.exportAnswer(request, ExportFormat.fromParam(format));
        return toResponse(file);
    }

    private ResponseEntity<byte[]> toResponse(ExportedFile file) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename())
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
