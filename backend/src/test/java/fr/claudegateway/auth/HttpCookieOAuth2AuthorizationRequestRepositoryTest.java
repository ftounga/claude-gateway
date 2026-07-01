package fr.claudegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import jakarta.servlet.http.Cookie;

/**
 * Tests unitaires du repository cookie du handshake OAuth : sérialisation aller-retour, attributs de
 * sécurité du cookie, et robustesse (cookie absent/corrompu → {@code null}, jamais d'exception).
 */
class HttpCookieOAuth2AuthorizationRequestRepositoryTest {

    private final HttpCookieOAuth2AuthorizationRequestRepository repository =
            new HttpCookieOAuth2AuthorizationRequestRepository();

    private OAuth2AuthorizationRequest sampleRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-client-id")
                .redirectUri("https://portal.ng-itconsulting.com/api/login/oauth2/code/google")
                .scopes(Set.of("openid", "email", "profile"))
                .state("state-xyz")
                .build();
    }

    @Test
    void saveWritesNonEmptyHttpOnlyLaxSecureCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveAuthorizationRequest(sampleRequest(), request, response);

        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull()
                .contains(HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME + "=")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Secure");
        assertThat(response.getCookie(HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME)
                .getValue()).isNotBlank();
    }

    @Test
    void secureFlagFollowsRequestScheme() {
        MockHttpServletRequest insecure = new MockHttpServletRequest();
        insecure.setSecure(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveAuthorizationRequest(sampleRequest(), insecure, response);

        assertThat(response.getHeader("Set-Cookie")).doesNotContain("Secure");
    }

    @Test
    void loadRestoresEquivalentAuthorizationRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(
                HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME, saveAndGetCookieValue()));

        OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(request);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getClientId()).isEqualTo("test-client-id");
        assertThat(loaded.getState()).isEqualTo("state-xyz");
        assertThat(loaded.getRedirectUri())
                .isEqualTo("https://portal.ng-itconsulting.com/api/login/oauth2/code/google");
        assertThat(loaded.getAuthorizationUri())
                .isEqualTo("https://accounts.google.com/o/oauth2/v2/auth");
    }

    @Test
    void removeReturnsRequestAndClearsCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(
                HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME, saveAndGetCookieValue()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthorizationRequest removed = repository.removeAuthorizationRequest(request, response);

        assertThat(removed).isNotNull();
        assertThat(response.getCookie(HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME)
                .getMaxAge()).isZero();
    }

    @Test
    void loadReturnsNullWhenCookieAbsent() {
        assertThat(repository.loadAuthorizationRequest(new MockHttpServletRequest())).isNull();
    }

    @Test
    void loadReturnsNullWhenCookieCorrupted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(
                HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME, "!!not-base64!!"));

        assertThat(repository.loadAuthorizationRequest(request)).isNull();
    }

    private String saveAndGetCookieValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(sampleRequest(), request, response);
        return response.getCookie(HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME).getValue();
    }
}
