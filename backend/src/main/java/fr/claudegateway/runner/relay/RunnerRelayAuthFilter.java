package fr.claudegateway.runner.relay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Garde du relais interne (F-38 / SF-38-12) : deux des trois barrières qui rendent
 * {@code /internal/**} inatteignable depuis l'ingress.
 *
 * <ol>
 *   <li><b>Port</b> — une requête reçue sur un autre port que le connecteur de relais est traitée
 *       comme si la route n'existait pas : <b>404, corps vide, aucune trace</b>. C'est la réponse au
 *       trafic venant de l'ingress, y compris si un jour quelqu'un ajoutait 8081 au Service public
 *       ou réécrivait la règle d'ingress.</li>
 *   <li><b>Secret</b> — en-tête {@code X-Internal-Relay-Secret} absent, vide ou différent :
 *       <b>401, corps vide</b>, sans {@code WWW-Authenticate} ni {@code ErrorResponse} — rien qui
 *       laisse deviner que la route existe. Comparaison en temps constant.</li>
 * </ol>
 *
 * <p>La troisième barrière est réseau : le port 8081 n'est publié que par le Service headless
 * {@code claude-gateway-backend-internal}, visé par aucun Ingress.</p>
 *
 * <p>Le filtre ne pose <b>rien</b> dans le {@code SecurityContext} : un secret de relais n'est pas
 * une identité d'utilisateur, exactement comme un jeton runner n'en est pas une (décision D9). Le
 * secret n'est jamais journalisé.</p>
 */
public class RunnerRelayAuthFilter extends OncePerRequestFilter {

    /** En-tête porteur du secret. Jamais {@code Authorization} : aucun filtre de sécurité ne doit tenter de l'interpréter. */
    public static final String SECRET_HEADER = "X-Internal-Relay-Secret";
    /** Identifiant d'instance de l'appelant — journalisation et corrélation uniquement. */
    public static final String ORIGIN_HEADER = "X-Relay-Origin";

    private final RunnerRelayConnectorCustomizer connector;
    private final byte[] expectedSecret;

    public RunnerRelayAuthFilter(RunnerRelayConnectorCustomizer connector, String secret) {
        this.connector = connector;
        this.expectedSecret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        if (request.getLocalPort() != connector.relayPort()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String presented = request.getHeader(SECRET_HEADER);
        if (presented == null || presented.isEmpty()
                || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedSecret)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
