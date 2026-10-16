package fr.claudegateway.atelier.besoin;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs du registre des besoins (F-205 / SF-205-01), traduites dans le paquet. Le 404 d'un terminal
 * d'autrui et le 403 de la garde admin continuent vers le gestionnaire global.
 */
@RestControllerAdvice(basePackageClasses = AppRequestExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AppRequestExceptionHandler {

    @ExceptionHandler(AppRequestInvalidException.class)
    public ResponseEntity<ErrorResponse> invalid(AppRequestInvalidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("app_request_invalid", ex.getMessage()));
    }

    @ExceptionHandler(AppRequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(AppRequestNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("app_request_not_found", ex.getMessage()));
    }

    @ExceptionHandler(AppRequestConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(AppRequestConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("app_request_conflict", ex.getMessage()));
    }
}
