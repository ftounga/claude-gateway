package fr.claudegateway.auth;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * SF-01-09 — échec au retour de Google : la cause est <b>journalisée</b> (code OAuth2 seul, jamais de jeton ni de
 * code d'autorisation) et la SPA reçoit {@code ${frontend}/auth/callback#error=<code>}, qui affiche l'échec.
 *
 * <p>Avant : la redirection par défaut de Spring ({@code /login?error}) tombait sur une ressource inexistante de
 * l'API, sans trace de la cause.</p>
 */
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginFailureHandler.class);

    private final String frontendUrl;

    public OAuth2LoginFailureHandler(String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        String code = errorCode(exception);
        log.warn("Connexion Google refusée au retour : code={}, cookie de requête présent={}, cause={}",
                code, hasAuthRequestCookie(request), exception.getClass().getSimpleName());
        response.sendRedirect(frontendUrl + "/auth/callback#error=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
    }

    static String errorCode(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth && oauth.getError() != null
                && oauth.getError().getErrorCode() != null) {
            return oauth.getError().getErrorCode();
        }
        return "oauth_failed";
    }

    private static boolean hasAuthRequestCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        return cookies != null && Arrays.stream(cookies)
                .anyMatch(c -> HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_NAME.equals(c.getName()));
    }
}
