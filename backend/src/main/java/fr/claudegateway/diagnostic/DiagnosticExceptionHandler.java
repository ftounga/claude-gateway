package fr.claudegateway.diagnostic;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs du diagnostic (F-157), traduites <b>dans le paquet</b>.
 *
 * <p>Le refus « ce projet n'est pas le dépôt » est une <b>demande mal formée</b>, pas une panne :
 * 400, avec le message qui dit quoi faire.</p>
 */
@RestControllerAdvice(basePackageClasses = DiagnosticExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DiagnosticExceptionHandler {

    @ExceptionHandler(RepositoryNotRecognizedException.class)
    public ResponseEntity<ErrorResponse> notTheRepository(RepositoryNotRecognizedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("not_the_repository", ex.getMessage()));
    }
}
