package fr.claudegateway.runner.door;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Le refus de la porte (F-161 / SF-161-01), traduit en <b>409</b>.
 *
 * <p>Pas un 400 : la requête est valide, c'est l'<b>état de la machine</b> qui ne l'est pas. Le
 * message dit <b>quoi faire</b> et que <b>rien n'a été dépensé</b> — c'est toute la valeur de ce
 * refus.</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RunnerDoorExceptionHandler {

    @ExceptionHandler(RunnerNotReadyException.class)
    public ResponseEntity<ErrorResponse> notReady(RunnerNotReadyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
