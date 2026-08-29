package fr.claudegateway.runner.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerTokenAuthenticator;

/**
 * Tests de l'authentification du handshake WebSocket runner (F-38 / SF-38-02) : jeton porté en query
 * param ou en sous-protocole, dépôt de l'identité dans les attributs, et refus (401) sans jeton valide.
 */
@ExtendWith(MockitoExtension.class)
class RunnerHandshakeInterceptorTest {

    @Mock
    private RunnerTokenAuthenticator authenticator;

    private RunnerHandshakeInterceptor interceptor() {
        return new RunnerHandshakeInterceptor(authenticator);
    }

    private ServletServerHttpRequest request(String queryString, String subprotocol) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/runner/ws");
        if (queryString != null) {
            req.setQueryString(queryString);
            // MockHttpServletRequest ne derive pas les params du queryString : les poser explicitement.
            for (String pair : queryString.split("&")) {
                String[] kv = pair.split("=", 2);
                req.setParameter(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        if (subprotocol != null) {
            req.addHeader("Sec-WebSocket-Protocol", subprotocol);
        }
        return new ServletServerHttpRequest(req);
    }

    private ServletServerHttpResponse response(MockHttpServletResponse raw) {
        return new ServletServerHttpResponse(raw);
    }

    @Test
    void validTokenInQueryParamDepositsIdentity() {
        RunnerIdentity identity = new RunnerIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(authenticator.authenticate(eq("jeton-valide"))).thenReturn(Optional.of(identity));
        Map<String, Object> attributes = new HashMap<>();
        MockHttpServletResponse raw = new MockHttpServletResponse();

        boolean allowed = interceptor().beforeHandshake(
                request("token=jeton-valide", null), response(raw), null, attributes);

        assertThat(allowed).isTrue();
        assertThat(attributes.get(RunnerHandshakeInterceptor.IDENTITY_ATTRIBUTE)).isEqualTo(identity);
    }

    @Test
    void validTokenInSubprotocolDepositsIdentity() {
        RunnerIdentity identity = new RunnerIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(authenticator.authenticate(eq("jeton-sp"))).thenReturn(Optional.of(identity));
        Map<String, Object> attributes = new HashMap<>();
        MockHttpServletResponse raw = new MockHttpServletResponse();

        boolean allowed = interceptor().beforeHandshake(
                request(null, "runner-token.jeton-sp"), response(raw), null, attributes);

        assertThat(allowed).isTrue();
        assertThat(attributes.get(RunnerHandshakeInterceptor.IDENTITY_ATTRIBUTE)).isEqualTo(identity);
    }

    @Test
    void invalidTokenIsRejectedWith401() {
        when(authenticator.authenticate(eq("mauvais"))).thenReturn(Optional.empty());
        Map<String, Object> attributes = new HashMap<>();
        MockHttpServletResponse raw = new MockHttpServletResponse();

        boolean allowed = interceptor().beforeHandshake(
                request("token=mauvais", null), response(raw), null, attributes);

        assertThat(allowed).isFalse();
        assertThat(attributes).doesNotContainKey(RunnerHandshakeInterceptor.IDENTITY_ATTRIBUTE);
        assertThat(raw.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void missingTokenIsRejectedWith401() {
        Map<String, Object> attributes = new HashMap<>();
        MockHttpServletResponse raw = new MockHttpServletResponse();

        boolean allowed = interceptor().beforeHandshake(
                request(null, null), response(raw), null, attributes);

        assertThat(allowed).isFalse();
        assertThat(raw.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }
}
