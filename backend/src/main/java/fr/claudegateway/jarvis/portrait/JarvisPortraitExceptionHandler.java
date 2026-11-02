package fr.claudegateway.jarvis.portrait;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/** Les erreurs du portrait (F-241), traduites dans le paquet ; le reste continue vers le gestionnaire global. */
@RestControllerAdvice(basePackageClasses = JarvisPortraitExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JarvisPortraitExceptionHandler {

    @ExceptionHandler({JarvisPortraitExceptions.InvalidPortraitException.class,
            JarvisPortraitExceptions.PortraitTooLargeException.class})
    public ResponseEntity<ErrorResponse> invalid(JarvisPortraitExceptions.PortraitException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(JarvisPortraitExceptions.SouvenirsFullException.class)
    public ResponseEntity<ErrorResponse> full(JarvisPortraitExceptions.SouvenirsFullException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(JarvisPortraitExceptions.SouvenirNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(JarvisPortraitExceptions.SouvenirNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
