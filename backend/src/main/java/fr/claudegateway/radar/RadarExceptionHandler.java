package fr.claudegateway.radar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs du Radar (F-99), traduites <b>dans le paquet</b> plutôt que dans le
 * {@code GlobalExceptionHandler} partagé. Limité aux contrôleurs du paquet {@code radar} ; toute
 * exception qu'il ne connaît pas continue vers le gestionnaire global.
 */
@RestControllerAdvice(basePackageClasses = RadarExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RadarExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RadarExceptionHandler.class);

    @ExceptionHandler(RadarNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(RadarNotFoundException ex) {
        log.debug("Objet Radar introuvable dans le périmètre");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", ex.getMessage()));
    }

    @ExceptionHandler(InvalidRadarInputException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidRadarInputException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("radar_invalid", ex.getMessage()));
    }

    @ExceptionHandler(RadarCorrectionConflictException.class)
    public ResponseEntity<ErrorResponse> correctionConflict(RadarCorrectionConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("radar_correction_conflict", ex.getMessage()));
    }

    @ExceptionHandler(RadarSubjectMergedException.class)
    public ResponseEntity<ErrorResponse> merged(RadarSubjectMergedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("radar_subject_merged", ex.getMessage()));
    }

    @ExceptionHandler(RadarStateConflictException.class)
    public ResponseEntity<ErrorResponse> stateConflict(RadarStateConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("radar_state_conflict", ex.getMessage()));
    }

    @ExceptionHandler(RadarRunnerUnavailableException.class)
    public ResponseEntity<ErrorResponse> runnerUnavailable(RadarRunnerUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("radar_runner_unavailable", ex.getMessage()));
    }

    @ExceptionHandler(RadarSyncRunningException.class)
    public ResponseEntity<ErrorResponse> syncRunning(RadarSyncRunningException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("radar_sync_running", ex.getMessage()));
    }

    @ExceptionHandler(RadarTeamsDisabledException.class)
    public ResponseEntity<ErrorResponse> teamsDisabled(RadarTeamsDisabledException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("radar_teams_disabled", ex.getMessage()));
    }

    @ExceptionHandler(RadarAnswerUnreadableException.class)
    public ResponseEntity<ErrorResponse> answerUnreadable(RadarAnswerUnreadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("radar_answer_unreadable", ex.getMessage()));
    }

    @ExceptionHandler(RadarEvidenceRequiredException.class)
    public ResponseEntity<ErrorResponse> evidenceRequired(RadarEvidenceRequiredException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("radar_evidence_required", ex.getMessage()));
    }
}
