package fr.claudegateway.atelier.actions;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs des actions du terminal (F-151), traduites <b>dans le paquet</b>. Limité aux contrôleurs
 * de {@code atelier.actions} ; toute autre exception continue vers le gestionnaire global.
 */
@RestControllerAdvice(basePackageClasses = TerminalActionExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TerminalActionExceptionHandler {

    @ExceptionHandler(TerminalActionNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(TerminalActionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", ex.getMessage()));
    }

    @ExceptionHandler(InvalidTerminalActionException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidTerminalActionException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("terminal_action_invalid", ex.getMessage()));
    }
}
