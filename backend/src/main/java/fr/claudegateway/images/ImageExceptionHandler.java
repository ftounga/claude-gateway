package fr.claudegateway.images;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs des images générées (F-142 / SF-142-04), traduites <b>dans le paquet</b> (même patron que les
 * pages et les présentations). Sans cela, le gestionnaire global mapperait toute {@code RuntimeException}
 * en 500 et masquerait le 404 d'isolation, le 400 de validation et le 503 « non configuré ». Toute
 * exception inconnue ici continue vers le global.
 */
@RestControllerAdvice(basePackageClasses = ImageExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ImageExceptionHandler {

    @ExceptionHandler(ImageNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(ImageNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", ex.getMessage()));
    }

    @ExceptionHandler(ImageRejectedException.class)
    public ResponseEntity<ErrorResponse> rejected(ImageRejectedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("image_rejected", ex.getMessage()));
    }

    @ExceptionHandler(ImageQuotaExceededException.class)
    public ResponseEntity<ErrorResponse> quota(ImageQuotaExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse("image_quota_exceeded", ex.getMessage()));
    }

    @ExceptionHandler(ImageProviderUnavailableException.class)
    public ResponseEntity<ErrorResponse> unavailable(ImageProviderUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("image_provider_unavailable", ex.getMessage()));
    }

    @ExceptionHandler(ImageProviderException.class)
    public ResponseEntity<ErrorResponse> providerError(ImageProviderException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("image_provider_error", ex.getMessage()));
    }
}
