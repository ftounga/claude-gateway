package fr.claudegateway.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/**
 * Chemin d'erreur du délai async (F-38 / SF-38-28). Le défaut tracé le 2026-09-18 était qu'un
 * dépassement async sur un flux {@code application/x-ndjson} finissait dans le filet
 * {@link GlobalExceptionHandler#handleUnexpected} qui tentait un {@code ErrorResponse} objet sur ce
 * flux ({@code HttpMessageNotWritableException}). Le handler dédié doit clôturer net les flux ndjson
 * / déjà engagés, et ne rendre un {@code 503} objet que sur une réponse JSON non engagée.
 */
class GlobalExceptionHandlerAsyncTimeoutTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void ndjsonResponseIsClosedCleanlyWithoutErrorResponse() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(MediaType.APPLICATION_NDJSON_VALUE);

        ResponseEntity<ErrorResponse> result =
                handler.handleAsyncTimeout(new AsyncRequestTimeoutException(), response);

        // null → rien n'est réécrit : jamais d'ErrorResponse objet sur un flux x-ndjson.
        assertThat(result).isNull();
    }

    @Test
    void committedResponseIsClosedCleanlyWithoutErrorResponse() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        ResponseEntity<ErrorResponse> result =
                handler.handleAsyncTimeout(new AsyncRequestTimeoutException(), response);

        assertThat(result).isNull();
    }

    @Test
    void uncommittedJsonResponseReturnsServiceUnavailable() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<ErrorResponse> result =
                handler.handleAsyncTimeout(new AsyncRequestTimeoutException(), response);

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().error()).isEqualTo("request_timeout");
    }
}
