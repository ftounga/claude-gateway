package fr.claudegateway.jarvis.companion;

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
 * <b>Un compagnon Android appairé</b> (F-232 / SF-232-03, D1b). La ligne naît à la génération du code d'appairage
 * ({@link #pairingCodeHash}) ; l'échange du code pose {@link #tokenHash} et {@link #pairedAt}, et efface le code.
 *
 * <p>Le jeton n'ouvre <b>que</b> la lecture des annonces Jarvis ({@code GET /runner/jarvis/announcements}). Seules les
 * empreintes SHA-256 sont stockées — jamais le clair. Échéance glissante : {@link #lastSeenAt} + 60 jours.</p>
 */
@Entity
@Table(name = "jarvis_companions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisCompanion {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation, et seule source du tenant des annonces. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "label", nullable = false, length = 80)
    private String label;

    @Column(name = "pairing_code_hash", length = 64)
    private String pairingCodeHash;

    @Column(name = "pairing_expires_at")
    private OffsetDateTime pairingExpiresAt;

    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(name = "paired_at")
    private OffsetDateTime pairedAt;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Appairé et non révoqué (l'échéance glissante se juge à part). */
    public boolean isActive() {
        return pairedAt != null && revokedAt == null && tokenHash != null;
    }
}
