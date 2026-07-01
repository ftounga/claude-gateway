package fr.claudegateway.upload;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.upload.dto.UploadResponse;

/**
 * Endpoint de téléversement F-04. L'identité provient exclusivement du {@link CurrentUser} (JWT) :
 * l'isolation {@code user_id} est appliquée dans le service, jamais depuis un paramètre client.
 */
@RestController
public class UploadController {

    private final UploadService uploadService;
    private final CurrentUser currentUser;

    public UploadController(UploadService uploadService, CurrentUser currentUser) {
        this.uploadService = uploadService;
        this.currentUser = currentUser;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestParam("file") MultipartFile file) {
        UUID userId = currentUser.requireId();
        return UploadResponse.from(uploadService.upload(userId, file));
    }
}
