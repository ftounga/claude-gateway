package fr.claudegateway.runner.channel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.RunnerTokenAuthenticator;

/**
 * Authentifie le handshake du canal runner (F-38 / SF-38-02). Le jeton runner (SF-38-01) est porté
 * soit en query param {@code ?token=...}, soit dans le sous-protocole
 * {@code Sec-WebSocket-Protocol: runner-token.<jeton>}. Un jeton valide dépose la
 * {@link RunnerIdentity} dans les attributs de session (clé {@link #IDENTITY_ATTRIBUTE}) ; un jeton
 * absent, inconnu, expiré ou révoqué fait <b>échouer</b> le handshake (401), sans distinction.
 *
 * <p>C'est le seul point d'authentification du WS : la chaîne de sécurité dédiée
 * {@code /runner/**} laisse passer {@code GET /runner/ws} ({@code permitAll}) et délègue ici.</p>
 */
@Component
public class RunnerHandshakeInterceptor implements HandshakeInterceptor {

    /** Clé sous laquelle la {@link RunnerIdentity} est déposée dans les attributs de session WS. */
    public static final String IDENTITY_ATTRIBUTE = "runnerIdentity";

    private static final String SUBPROTOCOL_PREFIX = "runner-token.";

    private final RunnerTokenAuthenticator authenticator;

    public RunnerHandshakeInterceptor(RunnerTokenAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Optional<RunnerIdentity> identity = extractToken(request).flatMap(authenticator::authenticate);
        if (identity.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(IDENTITY_ATTRIBUTE, identity.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // Rien à faire après le handshake.
    }

    private Optional<String> extractToken(ServerHttpRequest request) {
        // Query param `token` : lu du paramètre servlet (robuste), avec repli sur l'analyse de l'URI.
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String param = servletRequest.getServletRequest().getParameter("token");
            if (param != null) {
                return normalize(param);
            }
        }
        List<String> queryTokens = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().get("token");
        if (queryTokens != null && !queryTokens.isEmpty()) {
            return normalize(queryTokens.get(0));
        }
        List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols != null) {
            for (String raw : protocols) {
                for (String part : raw.split(",")) {
                    String candidate = part.trim();
                    if (candidate.startsWith(SUBPROTOCOL_PREFIX)) {
                        return normalize(candidate.substring(SUBPROTOCOL_PREFIX.length()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> normalize(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String trimmed = token.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }
}
