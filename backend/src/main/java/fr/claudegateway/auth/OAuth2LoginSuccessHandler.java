package fr.claudegateway.auth;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.util.StringUtils;

import fr.claudegateway.user.User;
import fr.claudegateway.user.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Handler de succès OAuth2/OIDC : à la fin du flux Google, fédère l'identité vers un {@link User}
 * de la plateforme, émet un <b>JWT plateforme</b> (même {@link JwtService} que l'auth locale) et
 * redirige la SPA vers {@code ${frontend}/auth/callback#token=<jwt>}.
 *
 * <p>Le token est transmis en <b>fragment d'URL</b> (jamais en query string) : il n'est ni envoyé
 * au serveur lors du chargement de la page, ni exposé dans les access logs / en-tête {@code Referer}.</p>
 */
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final UserService userService;
    private final JwtService jwtService;
    private final String frontendUrl;

    public OAuth2LoginSuccessHandler(UserService userService, JwtService jwtService, String frontendUrl) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {

        String email = extractEmail(authentication);
        if (!StringUtils.hasText(email)) {
            log.warn("Connexion Google sans e-mail exploitable — redirection d'erreur");
            response.sendRedirect(frontendUrl + "/auth/callback#error=email_unavailable");
            return;
        }

        User user = userService.findOrCreateGoogleUser(email.trim().toLowerCase());
        String token = jwtService.generateToken(user);
        // Le JWT ne doit jamais être journalisé.
        response.sendRedirect(frontendUrl + "/auth/callback#token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8));
    }

    private String extractEmail(Authentication authentication) {
        if (authentication.getPrincipal() instanceof OAuth2User oAuth2User) {
            Object email = oAuth2User.getAttributes().get("email");
            return email == null ? null : email.toString();
        }
        return null;
    }
}
