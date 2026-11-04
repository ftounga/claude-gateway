package fr.claudegateway.jarvis.calendar;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.shared.error.ErrorResponse;

/** Les erreurs des agendas (F-243), traduites dans le paquet ; le reste continue vers le gestionnaire global. */
@RestControllerAdvice(basePackageClasses = JarvisCalendarExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JarvisCalendarExceptionHandler {

    @ExceptionHandler({JarvisCalendarExceptions.InvalidFeedException.class,
            JarvisCalendarExceptions.FeedsFullException.class, JarvisCalendarExceptions.FeedGoneException.class,
            JarvisCalendarExceptions.NotACalendarException.class})
    public ResponseEntity<ErrorResponse> invalid(JarvisCalendarExceptions.CalendarException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(JarvisCalendarExceptions.FeedUnavailableException.class)
    public ResponseEntity<ErrorResponse> unavailable(JarvisCalendarExceptions.CalendarException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(JarvisCalendarExceptions.FeedNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(JarvisCalendarExceptions.CalendarException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
