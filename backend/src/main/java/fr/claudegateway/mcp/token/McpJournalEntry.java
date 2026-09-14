package fr.claudegateway.mcp.token;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Une ligne du <b>journal MCP</b> (F-112 / SF-112-03, cadrage §6.6) : un appel d'outil, <b>sans son
 * contenu</b>. Les paramètres sont résumés par leurs clés ({@link #paramsSummary}), jamais leurs
 * valeurs. Cloisonné par {@link #userId}.
 */
@Entity
@Table(name = "mcp_journal")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpJournalEntry {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Nom lisible du client (jeton personnel : son nom ; OAuth : le nom du client). */
    @Column(name = "client", length = 200)
    private String client;

    /** Jeton personnel utilisé, s'il y a lieu. */
    @Column(name = "token_id")
    private UUID tokenId;

    /** {@code PERSONAL} ou {@code OAUTH}. */
    @Column(name = "auth_kind", nullable = false, length = 16)
    private String authKind;

    @Column(name = "tool", length = 120)
    private String tool;

    @Column(name = "host_id")
    private UUID hostId;

    /** Résumé des paramètres <b>par leurs clés</b>, jamais leurs valeurs. */
    @Column(name = "params_summary", length = 500)
    private String paramsSummary;

    /** {@code OK}, {@code ERROR} ou {@code DENIED}. */
    @Column(name = "result", nullable = false, length = 16)
    private String result;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
