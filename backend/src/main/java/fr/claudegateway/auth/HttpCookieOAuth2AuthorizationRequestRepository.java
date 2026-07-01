package fr.claudegateway.auth;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Stocke l'{@link OAuth2AuthorizationRequest} du handshake OAuth2/OIDC dans un <b>cookie court</b>
 * plutôt que dans la {@code HttpSession} serveur.
 *
 * <p>Motivation : la chaîne de sécurité est {@code SessionCreationPolicy.STATELESS} et le backend
 * tourne derrière l'ingress avec plusieurs réplicas possibles (HPA). Une {@code HttpSession} en
 * mémoire d'un pod ne survivrait pas de façon fiable au retour du callback si celui-ci atterrit sur
 * un autre pod (pas de session collante). Le cookie rend le handshake <b>auto-porteur</b>.</p>
 *
 * <p>Sécurité : cookie {@code HttpOnly}, {@code Secure} en HTTPS, {@code SameSite=Lax} (indispensable
 * pour être renvoyé lors de la redirection top-level depuis Google), durée de vie ≤ 3 min. Le contenu
 * se limite à l'authorization request OAuth (jamais de JWT ni de secret). La désérialisation est
 * bornée par un {@link ObjectInputFilter} en liste blanche : un cookie forgé ne peut pas instancier
 * de classes arbitraires.</p>
 */
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String COOKIE_NAME = "oauth2_auth_request";
    private static final Duration COOKIE_TTL = Duration.ofSeconds(180);

    /**
     * Liste blanche de désérialisation : seules les classes composant une {@link OAuth2AuthorizationRequest}
     * sont autorisées ({@code !*} rejette tout le reste). Bornes anti-abus sur un cookie forgé.
     */
    private static final ObjectInputFilter DESERIALIZATION_FILTER = ObjectInputFilter.Config.createFilter(
            "maxbytes=8192;maxdepth=20;maxrefs=1000;"
            + "org.springframework.security.oauth2.core.**;"
            + "java.util.**;java.lang.**;java.time.**;java.net.**;!*");

    private static final Logger log =
            LoggerFactory.getLogger(HttpCookieOAuth2AuthorizationRequestRepository.class);

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return readCookieValue(request).map(this::deserialize).orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request, HttpServletResponse response) {
        // Spring appelle save(null) pour effacer : on supprime le cookie.
        if (authorizationRequest == null) {
            deleteCookie(request, response);
            return;
        }
        ResponseCookie cookie = baseCookie(serialize(authorizationRequest), request)
                .maxAge(COOKIE_TTL)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        if (authorizationRequest != null) {
            deleteCookie(request, response);
        }
        return authorizationRequest;
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value, HttpServletRequest request) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .path("/")
                .httpOnly(true)
                // request.isSecure() reflète X-Forwarded-Proto grâce à forward-headers-strategy.
                .secure(request.isSecure())
                .sameSite("Lax");
    }

    private void deleteCookie(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie = baseCookie("", request).maxAge(0).build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private Optional<String> readCookieValue(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(StringUtils::hasText)
                .findFirst();
    }

    private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(authorizationRequest);
            oos.flush();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            // Écriture en mémoire : ne devrait jamais échouer.
            throw new IllegalStateException("Échec de sérialisation de l'authorization request OAuth", e);
        }
    }

    /**
     * Désérialise le cookie en {@link OAuth2AuthorizationRequest}. Un cookie absent, illisible ou
     * corrompu renvoie {@code null} (jamais d'exception) : le flux OAuth échoue proprement (302),
     * sans 500.
     */
    private OAuth2AuthorizationRequest deserialize(String value) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            log.debug("Cookie OAuth illisible (Base64 invalide) — ignoré");
            return null;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            ois.setObjectInputFilter(DESERIALIZATION_FILTER);
            Object object = ois.readObject();
            return object instanceof OAuth2AuthorizationRequest authorizationRequest
                    ? authorizationRequest
                    : null;
        } catch (Exception e) {
            log.debug("Cookie OAuth non désérialisable — ignoré");
            return null;
        }
    }
}
