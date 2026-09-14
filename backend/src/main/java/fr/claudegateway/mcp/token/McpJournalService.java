package fr.claudegateway.mcp.token;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Écrit et relit le journal MCP (F-112 / SF-112-03). L'écriture est <b>best-effort</b> : un échec de
 * journalisation ne casse jamais un appel d'outil. Le journal ne contient <b>jamais</b> de contenu.
 */
@Service
public class McpJournalService {

    private static final Logger log = LoggerFactory.getLogger(McpJournalService.class);
    private static final int MAX_ENTRIES = 200;

    private final McpJournalRepository repository;

    public McpJournalService(McpJournalRepository repository) {
        this.repository = repository;
    }

    /** Enregistre un appel d'outil (best-effort). {@code paramsSummary} = clés seulement, jamais valeurs. */
    @Transactional
    public void record(UUID userId, String client, UUID tokenId, String authKind, String tool,
            UUID hostId, String paramsSummary, String result, long durationMs) {
        try {
            repository.save(McpJournalEntry.builder()
                    .userId(userId)
                    .client(truncate(client, 200))
                    .tokenId(tokenId)
                    .authKind(authKind)
                    .tool(truncate(tool, 120))
                    .hostId(hostId)
                    .paramsSummary(truncate(paramsSummary, 500))
                    .result(result)
                    .durationMs(durationMs)
                    .createdAt(OffsetDateTime.now())
                    .build());
        } catch (RuntimeException ex) {
            log.warn("Journal MCP : écriture ignorée ({})", ex.getClass().getSimpleName());
        }
    }

    @Transactional(readOnly = true)
    public List<McpJournalEntry> recentForUser(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, MAX_ENTRIES));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
