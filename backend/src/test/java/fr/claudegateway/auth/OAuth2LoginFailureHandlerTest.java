package fr.claudegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** SF-01-09 — l'échec du retour Google renvoie la SPA vers son écran d'échec, avec le code OAuth2. */
class OAuth2LoginFailureHandlerTest {

    private final OAuth2LoginFailureHandler handler = new OAuth2LoginFailureHandler("https://portal.example");

    @Test
    void redirectsToSpaCallbackWithOAuthErrorCode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("authorization_request_not_found")));
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://portal.example/auth/callback#error=authorization_request_not_found");
    }

    @Test
    void genericFailureGetsGenericCode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(new MockHttpServletRequest(), response, new BadCredentialsException("x"));
        assertThat(response.getRedirectedUrl()).isEqualTo("https://portal.example/auth/callback#error=oauth_failed");
    }
}
