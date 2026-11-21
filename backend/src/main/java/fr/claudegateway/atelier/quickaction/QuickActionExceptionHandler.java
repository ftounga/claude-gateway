package fr.claudegateway.atelier.quickaction;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs des actions rapides (F-275), traduites dans le paquet. Le 404 d'un terminal d'autrui
 * ({@code WorkspaceNotFoundException}) continue vers le gestionnaire global.
 */
@RestControllerAdvice(basePackageClasses = QuickActionExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QuickActionExceptionHandler {

    @ExceptionHandler(QuickActionException.class)
    public ResponseEntity<ErrorResponse> handle(QuickActionException ex) {
        HttpStatus status = switch (ex.kind()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
