package fr.claudegateway.atelier.veille;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.atelier.veille.JarvisWatchExceptions.InvalidWatchException;
import fr.claudegateway.atelier.veille.JarvisWatchExceptions.UnsafeProbeException;
import fr.claudegateway.atelier.veille.JarvisWatchExceptions.WatchConflictException;
import fr.claudegateway.atelier.veille.JarvisWatchExceptions.WatchNotFoundException;
import fr.claudegateway.atelier.veille.JarvisWatchExceptions.WatchUnsupportedException;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs des veilles (F-230), traduites dans le paquet. Le 404 d'un sujet d'autrui
 * ({@code WorkspaceNotFoundException}) continue vers le gestionnaire global.
 */
@RestControllerAdvice(basePackageClasses = JarvisWatchExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JarvisWatchExceptionHandler {

    @ExceptionHandler({InvalidWatchException.class, UnsafeProbeException.class, WatchUnsupportedException.class})
    public ResponseEntity<ErrorResponse> invalid(JarvisWatchExceptions.WatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(WatchNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(WatchNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(WatchConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(WatchConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
