package fr.claudegateway.runner;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
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
 * Jeton d'authentification d'un runner (F-38 / SF-38-01), lié à un utilisateur et à un workspace,
 * expirant et révocable. Il authentifiera le canal WebSocket du runner (SF-38-02).
 *
 * <p>Le jeton ouvre un canal d'exécution : on ne stocke que son empreinte {@code SHA-256}
 * ({@link #tokenHash}), jamais le clair. Le clair n'existe que dans la réponse HTTP de
 * {@code POST /runner/pair}.</p>
 */
@Entity
@Table(name = "runner_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RunnerToken {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Workspace auquel le runner est rattaché (= {@code workspaces.id}). */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /** Empreinte SHA-256 (hex) du jeton. Unique. Jamais le clair. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64, updatable = false)
    private String tokenHash;

    /** Libellé lisible choisi à l'appairage (facultatif). */
    @Column(name = "label", length = 100)
    private String label;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    /** Renseigné à la révocation. Un jeton révoqué n'authentifie plus. */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /** Dernière activité observée du runner (mis à jour par le heartbeat, SF-38-02). */
    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Vrai si le jeton n'est ni révoqué ni expiré à l'instant donné. */
    public boolean isValidAt(OffsetDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
