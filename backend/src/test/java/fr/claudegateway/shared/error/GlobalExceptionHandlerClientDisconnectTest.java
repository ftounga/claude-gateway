package fr.claudegateway.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * Chemins de robustesse du flux au proxy corporate (F-170 / SF-170-01).
 *
 * <p>Un client déconnecté ({@link AsyncRequestNotUsableException}) ne doit jamais remonter comme panne
 * ni déclencher l'écriture d'un {@code ErrorResponse}. Et une {@link HttpMessageNotWritableException}
 * sur un flux déjà engagé / ndjson / event-stream doit être close net — c'est la fin de la cascade
 * « No converter for [ErrorResponse] with preset Content-Type 'application/x-ndjson' » tracée en prod.</p>
 */
class GlobalExceptionHandlerClientDisconnectTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void clientDeconnecteEstCloturesSansErrorResponse() {
        ResponseEntity<ErrorResponse> result =
                handler.handleClientDisconnected(new AsyncRequestNotUsableException("disconnected client"));

        // null → rien n'est réécrit vers un client déjà parti.
        assertThat(result).isNull();
    }

    @Test
    void ecritureImpossibleSurFluxNdjsonEstCloturesSansErrorResponse() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(MediaType.APPLICATION_NDJSON_VALUE);

        ResponseEntity<ErrorResponse> result =
                handler.handleNotWritable(new HttpMessageNotWritableException("No converter"), response);

        assertThat(result).isNull();
    }

    @Test
    void ecritureImpossibleSurFluxEventStreamEstCloturesSansErrorResponse() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);

        ResponseEntity<ErrorResponse> result =
                handler.handleNotWritable(new HttpMessageNotWritableException("No converter"), response);

        assertThat(result).isNull();
    }

    @Test
    void ecritureImpossibleSurReponseDejaEngageeEstCloturesSansErrorResponse() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        ResponseEntity<ErrorResponse> result =
                handler.handleNotWritable(new HttpMessageNotWritableException("No converter"), response);

        assertThat(result).isNull();
    }

    @Test
    void ecritureImpossibleSurJsonNonEngageRendUne500() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<ErrorResponse> result =
                handler.handleNotWritable(new HttpMessageNotWritableException("boom"), response);

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().error()).isEqualTo("internal_error");
    }
}
