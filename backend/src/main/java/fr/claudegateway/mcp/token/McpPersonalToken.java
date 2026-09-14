package fr.claudegateway.mcp.token;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Un <b>jeton personnel</b> MCP (F-112 / SF-112-03) : le moyen d'accès sans navigateur (script,
 * intégration continue) au serveur MCP.
 *
 * <p>Le secret n'est <b>jamais</b> stocké en clair : seul son {@link #tokenHash} (SHA-256) l'est.
 * L'expiration ({@link #expiresAt}) est <b>obligatoire</b> (≤ 90 jours, imposé par le service).
 * L'accès est donné <b>poste par poste</b> ({@link #hostIds}) : un poste non listé n'est pas
 * accessible. {@link #userId} est la racine de l'isolation.</p>
 */
@Entity
@Table(name = "mcp_personal_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpPersonalToken {

    public static final int MAX_NAME_LENGTH = 120;
    public static final int MAX_EXPIRY_DAYS = 90;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    /** SHA-256 hex du secret. Le clair n'existe qu'au moment de la création. */
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    /** Préfixe affichable (ex. {@code cgmcp_ab12…}) pour reconnaître le jeton dans la liste. */
    @Column(name = "token_prefix", nullable = false, length = 24, updatable = false)
    private String tokenPrefix;

    /** Périmètres accordés, séparés par des espaces. */
    @Column(name = "scopes", nullable = false, length = 500)
    private String scopes;

    @ElementCollection
    @CollectionTable(name = "mcp_token_hosts", joinColumns = @JoinColumn(name = "token_id"))
    @Column(name = "host_id")
    @Builder.Default
    private Set<UUID> hostIds = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /** Actif = non révoqué et non expiré à l'instant donné. */
    public boolean isActiveAt(OffsetDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
