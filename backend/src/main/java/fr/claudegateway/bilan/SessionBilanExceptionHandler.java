package fr.claudegateway.bilan;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/** Erreurs des bilans (F-155), traduites <b>dans le paquet</b>. */
@RestControllerAdvice(basePackageClasses = SessionBilanExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SessionBilanExceptionHandler {

    @ExceptionHandler(SessionBilanNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(SessionBilanNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", ex.getMessage()));
    }
}
