package fr.claudegateway.atelier.proposal;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs des propositions de gouvernance (F-177 / SF-177-02), traduites dans le paquet. Le 404 d'un
 * terminal d'autrui continue vers le gestionnaire global.
 */
@RestControllerAdvice(basePackageClasses = GovernanceProposalExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GovernanceProposalExceptionHandler {

    @ExceptionHandler(InvalidProposalException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidProposalException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("proposal_invalid", ex.getMessage()));
    }

    @ExceptionHandler(ProposalNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(ProposalNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("proposal_not_found", ex.getMessage()));
    }

    @ExceptionHandler(ProposalConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(ProposalConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("proposal_conflict", ex.getMessage()));
    }

    @ExceptionHandler(ProposalUnreachableException.class)
    public ResponseEntity<ErrorResponse> unreachable(ProposalUnreachableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("proposal_host_unreachable", ex.getMessage()));
    }
}
