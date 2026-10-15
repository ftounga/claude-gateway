package fr.claudegateway.atelier.routine;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.atelier.routine.RoutineExceptions.InvalidRoutineException;
import fr.claudegateway.atelier.routine.RoutineExceptions.RoutineConflictException;
import fr.claudegateway.atelier.routine.RoutineExceptions.RoutineNotFoundException;
import fr.claudegateway.atelier.routine.RoutineExceptions.RoutineUnsupportedException;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs des routines (F-183), traduites dans le paquet. Le 404 d'un sujet d'autrui
 * ({@code WorkspaceNotFoundException}) continue vers le gestionnaire global.
 */
@RestControllerAdvice(basePackageClasses = RoutineExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RoutineExceptionHandler {

    @ExceptionHandler({InvalidRoutineException.class, RoutineUnsupportedException.class})
    public ResponseEntity<ErrorResponse> invalid(RoutineExceptions.RoutineException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(RoutineNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(RoutineNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(RoutineConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(RoutineConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
