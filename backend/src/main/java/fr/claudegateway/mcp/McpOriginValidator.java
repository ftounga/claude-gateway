package fr.claudegateway.mcp;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

/**
 * Protection anti-rebinding DNS pour le serveur MCP : refuse une requête dont l'en-tête
 * {@code Origin} est présent mais absent de l'allowlist ({@code app.mcp.allowed-origins}).
 *
 * <p>Un client IA sans navigateur (Claude Code, Codex…) n'envoie pas d'{@code Origin} : ces requêtes
 * passent (l'authentification par jeton reste exigée par la chaîne de sécurité). Un navigateur, lui,
 * envoie toujours un {@code Origin} : c'est le vecteur de rebinding, et c'est celui que l'on borne.
 * Allowlist vide → tout {@code Origin} est accepté (posture de développement), avec un avertissement
 * au démarrage.</p>
 */
@Component
public class McpOriginValidator implements ServerTransportSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(McpOriginValidator.class);
    private static final String ORIGIN_HEADER = "origin";

    private final List<String> allowedOrigins;

    public McpOriginValidator(
            @Value("${app.mcp.allowed-origins:}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .map(String::trim)
                .filter(o -> !o.isEmpty())
                .map(o -> o.toLowerCase(Locale.ROOT))
                .toList();
        if (this.allowedOrigins.isEmpty()) {
            log.warn("MCP : aucune origine autorisée configurée (app.mcp.allowed-origins) — toute "
                    + "origine de navigateur est acceptée. À restreindre en production.");
        }
    }

    @Override
    public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
        String origin = firstHeader(headers, ORIGIN_HEADER);
        if (origin == null || allowedOrigins.isEmpty()) {
            return;
        }
        if (!allowedOrigins.contains(origin.toLowerCase(Locale.ROOT))) {
            throw new ServerTransportSecurityException(403, "Origine non autorisée");
        }
    }

    /** Lecture d'en-tête insensible à la casse (les cartes d'en-têtes HTTP ne garantissent pas la casse). */
    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                List<String> values = entry.getValue();
                return values == null || values.isEmpty() ? null : values.get(0);
            }
        }
        return null;
    }
}
