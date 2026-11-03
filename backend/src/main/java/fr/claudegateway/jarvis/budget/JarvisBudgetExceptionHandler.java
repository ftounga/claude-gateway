package fr.claudegateway.jarvis.budget;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Le budget de Jarvis en réponse JSON (F-244 / SF-244-03) : la porte d'une délégation (tour non streamé) rend 429 avec
 * son code et la phrase à dire ; un réglage hors bornes rend 400.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JarvisBudgetExceptionHandler {

    @ExceptionHandler(JarvisBudgetException.class)
    public ResponseEntity<ErrorResponse> budget(JarvisBudgetException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
