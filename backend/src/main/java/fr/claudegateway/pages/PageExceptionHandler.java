package fr.claudegateway.pages;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import fr.claudegateway.pages.pdf.PagePdfRejectedException;
import fr.claudegateway.pages.pdf.PagePdfUnavailableException;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * Erreurs des pages (F-109), traduites <b>dans le paquet</b> (même patron que le Radar). Toute exception
 * inconnue ici continue vers le gestionnaire global.
 */
@RestControllerAdvice(basePackageClasses = PageExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PageExceptionHandler {

    @ExceptionHandler(PageNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(PageNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("not_found", ex.getMessage()));
    }

    @ExceptionHandler(PageQuotaExceededException.class)
    public ResponseEntity<ErrorResponse> quota(PageQuotaExceededException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("page_quota_exceeded", ex.getMessage()));
    }

    @ExceptionHandler(PageRejectedException.class)
    public ResponseEntity<ErrorResponse> rejected(PageRejectedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("page_rejected", ex.getMessage()));
    }

    /** F-184 / SF-184-02 : le moteur PDF est absent ou muet — rien à voir avec la page. */
    @ExceptionHandler(PagePdfUnavailableException.class)
    public ResponseEntity<ErrorResponse> pdfUnavailable(PagePdfUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("page_pdf_unavailable", ex.getMessage()));
    }

    /** F-184 / SF-184-02 : le moteur PDF a refusé le lot — sa raison est relayée. */
    @ExceptionHandler(PagePdfRejectedException.class)
    public ResponseEntity<ErrorResponse> pdfRejected(PagePdfRejectedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("page_pdf_rejected", ex.getMessage()));
    }
}
