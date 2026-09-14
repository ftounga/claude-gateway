package fr.claudegateway.mcp.token;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accès aux jetons personnels MCP (F-112 / SF-112-03). Toute lecture propre à un utilisateur passe
 * par {@code user_id} (isolation) ; la recherche par hachage sert l'authentification sur {@code /mcp}.
 */
public interface McpPersonalTokenRepository extends JpaRepository<McpPersonalToken, UUID> {

    List<McpPersonalToken> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<McpPersonalToken> findByIdAndUserId(UUID id, UUID userId);

    Optional<McpPersonalToken> findByTokenHash(String tokenHash);
}
