package fr.claudegateway.mail;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs du courriel du client (F-110), traduites dans le paquet. Toute autre exception continue vers le
 * gestionnaire global (poste introuvable → 404, droit absent → 403).
 */
@RestControllerAdvice(basePackageClasses = MailExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MailExceptionHandler {

    @ExceptionHandler(MailAddressException.class)
    public ResponseEntity<ErrorResponse> refused(MailAddressException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
