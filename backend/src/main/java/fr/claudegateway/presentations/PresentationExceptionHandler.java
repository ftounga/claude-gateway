package fr.claudegateway.presentations;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs des présentations (F-129), traduites <b>dans le paquet</b> (même patron que les pages et le
 * Radar). Sans cela, le gestionnaire global mapperait toute {@code RuntimeException} en 500 et masquerait
 * le 404 d'isolation et le 400 de validation. Toute exception inconnue ici continue vers le global.
 */
@RestControllerAdvice(basePackageClasses = PresentationExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PresentationExceptionHandler {

    @ExceptionHandler(PresentationNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(PresentationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", ex.getMessage()));
    }

    @ExceptionHandler(PresentationRejectedException.class)
    public ResponseEntity<ErrorResponse> rejected(PresentationRejectedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("presentation_rejected", ex.getMessage()));
    }
}
