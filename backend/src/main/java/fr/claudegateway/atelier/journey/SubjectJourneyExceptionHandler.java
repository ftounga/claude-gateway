package fr.claudegateway.atelier.journey;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs du parcours (F-176), traduites <b>dans le paquet</b>. Limité aux contrôleurs de
 * {@code atelier.journey} ; toute autre exception (dont le 404 d'un terminal d'autrui) continue vers
 * le gestionnaire global.
 */
@RestControllerAdvice(basePackageClasses = SubjectJourneyExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SubjectJourneyExceptionHandler {

    @ExceptionHandler(InvalidJourneyException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidJourneyException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("journey_invalid", ex.getMessage()));
    }
}
