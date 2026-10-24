package fr.claudegateway.videos;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/** Les erreurs des routes vidéo (F-213) : 404 indiscernable, 400 avec le motif — jamais de pile. */
@RestControllerAdvice(basePackageClasses = VideoExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class VideoExceptionHandler {

    @ExceptionHandler(VideoNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(VideoNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("not_found", ex.getMessage()));
    }

    @ExceptionHandler(VideoRejectedException.class)
    public ResponseEntity<ErrorResponse> rejected(VideoRejectedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("video_rejected", ex.getMessage()));
    }
}
