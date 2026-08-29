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
 * Code d'appairage à usage unique et expirant (F-38 / SF-38-01). Généré par l'utilisateur pour un
 * de ses workspaces, il est échangé par le runner contre un {@link RunnerToken} via
 * {@code POST /runner/pair}.
 *
 * <p>La valeur en clair n'est jamais persistée : seul son empreinte {@code SHA-256}
 * ({@link #codeHash}) est stockée. Le clair n'existe que dans la réponse HTTP de génération.</p>
 */
@Entity
@Table(name = "runner_pairing_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RunnerPairingCode {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire du code (= {@code users.id}). Filtre d'isolation. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Workspace visé par l'appairage (= {@code workspaces.id}). */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /** Empreinte SHA-256 (hex) du code d'appairage. Jamais le clair. */
    @Column(name = "code_hash", nullable = false, length = 64, updatable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    /** Renseigné à l'échange : le code est à usage unique. */
    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Vrai si le code n'a pas encore été consommé et n'est pas expiré à l'instant donné. */
    public boolean isUsableAt(OffsetDateTime now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }
}
