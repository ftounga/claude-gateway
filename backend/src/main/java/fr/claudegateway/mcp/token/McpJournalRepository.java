package fr.claudegateway.mcp.token;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accès au journal MCP (F-112 / SF-112-03). La lecture est toujours filtrée par {@code user_id}.
 */
public interface McpJournalRepository extends JpaRepository<McpJournalEntry, UUID> {

    List<McpJournalEntry> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
