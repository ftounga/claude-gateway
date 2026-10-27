package fr.claudegateway.atelier.autonomy;

import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.atelier.journey.ChantierPreflight;
import fr.claudegateway.atelier.journey.InvalidJourneyException;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs du départ en autonome (F-219), traduites <b>dans le paquet</b> ; le 404 d'un terminal d'autrui
 * continue vers le gestionnaire global. Jamais de trace de pile dans la réponse.
 */
@RestControllerAdvice(basePackageClasses = AutonomyExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AutonomyExceptionHandler {

    /** Le corps d'un refus : le code, la phrase, et les raisons du préflight s'il y en a. */
    public record ConflictBody(String error, String message, List<ChantierPreflight.Reason> reasons) {
    }

    @ExceptionHandler(AutonomyConflictException.class)
    public ResponseEntity<ConflictBody> conflict(AutonomyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ConflictBody(ex.code(), ex.getMessage(), ex.reasons()));
    }

    @ExceptionHandler(InvalidJourneyException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidJourneyException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("journey_invalid", ex.getMessage()));
    }
}
