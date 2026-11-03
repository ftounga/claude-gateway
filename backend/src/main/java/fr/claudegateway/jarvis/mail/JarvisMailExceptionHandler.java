package fr.claudegateway.jarvis.mail;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/** Les erreurs du courrier (F-242), traduites dans le paquet ; le reste continue vers le gestionnaire global. */
@RestControllerAdvice(basePackageClasses = JarvisMailExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JarvisMailExceptionHandler {

    @ExceptionHandler({JarvisMailExceptions.InvalidMailAccountException.class,
            JarvisMailExceptions.MailAccountsFullException.class, JarvisMailExceptions.MailAuthException.class})
    public ResponseEntity<ErrorResponse> invalid(JarvisMailExceptions.MailException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(JarvisMailExceptions.MailUnavailableException.class)
    public ResponseEntity<ErrorResponse> unavailable(JarvisMailExceptions.MailException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(JarvisMailExceptions.MailNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(JarvisMailExceptions.MailException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
