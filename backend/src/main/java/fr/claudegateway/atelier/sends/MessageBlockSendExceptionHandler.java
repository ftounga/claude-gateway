package fr.claudegateway.atelier.sends;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs des envois marqués (F-211), traduites <b>dans le paquet</b> ; toute autre exception continue
 * vers le gestionnaire global (dont le 404 d'un terminal d'autrui).
 */
@RestControllerAdvice(basePackageClasses = MessageBlockSendExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MessageBlockSendExceptionHandler {

    @ExceptionHandler(MessageBlockSendNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(MessageBlockSendNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", ex.getMessage()));
    }

    @ExceptionHandler(InvalidMessageBlockSendException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidMessageBlockSendException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("message_block_send_invalid", ex.getMessage()));
    }
}
