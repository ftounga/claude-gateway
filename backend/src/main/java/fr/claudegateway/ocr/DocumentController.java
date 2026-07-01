package fr.claudegateway.ocr;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.ocr.dto.DocumentDetailResponse;
import fr.claudegateway.ocr.dto.DocumentResponse;

/**
 * Endpoints du pipeline OCR (F-05). L'identité provient exclusivement du {@link CurrentUser} (JWT) :
 * l'isolation {@code user_id} est appliquée dans le service, jamais depuis un paramètre client.
 * Aucune logique métier ici (validation/traitement dans {@link DocumentService}).
 */
@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final CurrentUser currentUser;

    public DocumentController(DocumentService documentService, CurrentUser currentUser) {
        this.documentService = documentService;
        this.currentUser = currentUser;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse submit(@RequestParam("file") MultipartFile file) {
        UUID userId = currentUser.requireId();
        return DocumentResponse.from(documentService.submit(userId, file));
    }

    @GetMapping
    public List<DocumentResponse> list() {
        UUID userId = currentUser.requireId();
        return documentService.list(userId).stream().map(DocumentResponse::from).toList();
    }

    @GetMapping("/{id}")
    public DocumentDetailResponse get(@PathVariable("id") UUID id) {
        UUID userId = currentUser.requireId();
        return DocumentDetailResponse.from(documentService.getById(userId, id));
    }
}
