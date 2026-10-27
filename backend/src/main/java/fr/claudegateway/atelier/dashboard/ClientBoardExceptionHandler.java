package fr.claudegateway.atelier.dashboard;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.atelier.journey.InvalidJourneyException;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs du tableau du client (F-220), traduites <b>dans le paquet</b> ; le 404 d'un terminal ou d'un poste
 * d'autrui continue vers le gestionnaire global. Jamais de trace de pile dans la réponse.
 */
@RestControllerAdvice(basePackageClasses = ClientBoardExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClientBoardExceptionHandler {

    @ExceptionHandler(InvalidJourneyException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidJourneyException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("journey_invalid", ex.getMessage()));
    }
}
