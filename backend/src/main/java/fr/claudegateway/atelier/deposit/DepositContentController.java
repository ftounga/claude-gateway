package fr.claudegateway.atelier.deposit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * <b>L'aperçu d'une pièce jointe</b> (F-228 / SF-228-01) : {@code GET /workspaces/{id}/deposits/{depositId}/content}.
 * L'identité vient du {@link CurrentUser} ; l'isolation est dans le service. La réponse ne s'exécute jamais dans le
 * navigateur : {@code nosniff}, et une CSP {@code sandbox} (un SVG ou un PDF ouvert seul n'a aucun droit).
 */
@RestController
public class DepositContentController {

    private final DepositContentService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public DepositContentController(DepositContentService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @GetMapping("/workspaces/{workspaceId}/deposits/{depositId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID workspaceId, @PathVariable UUID depositId) {
        atelierAccess.requireAccess();
        DepositContentService.Content content = service.content(currentUser.requireId(), workspaceId, depositId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(content.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; "
                        + "media-src 'self'; style-src 'unsafe-inline'")
                .cacheControl(CacheControl.noStore())
                .body(content.bytes());
    }

    /**
     * <b>Télécharger une pièce</b> (F-228 / SF-228-04) : {@code GET /workspaces/{id}/deposits/{depositId}/download},
     * en flux, jusqu'à 100 Mo. Toujours des octets à enregistrer ({@code attachment}, {@code octet-stream},
     * {@code nosniff}) : rien ne s'ouvre dans le navigateur. Les en-têtes ne sont posés qu'à la première tranche lue —
     * une erreur avant reste une réponse JSON lisible (404, 503).
     */
    @GetMapping("/workspaces/{workspaceId}/deposits/{depositId}/download")
    public void download(@PathVariable UUID workspaceId, @PathVariable UUID depositId, HttpServletResponse response)
            throws IOException {
        atelierAccess.requireAccess();
        service.download(currentUser.requireId(), workspaceId, depositId, fileName -> {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
            response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
            return response.getOutputStream();
        });
    }

    /** Les erreurs de l'aperçu, traduites dans le paquet ; le reste (404 d'un terminal d'autrui) va au global. */
    @RestControllerAdvice(basePackageClasses = DepositContentController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class Errors {

        @ExceptionHandler(DepositNotFoundException.class)
        public ResponseEntity<ErrorResponse> notFound(DepositNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("not_found", ex.getMessage()));
        }

        @ExceptionHandler(DepositUnreadableException.class)
        public ResponseEntity<ErrorResponse> unreadable(DepositUnreadableException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("deposit_unreadable", ex.getMessage()));
        }
    }
}
