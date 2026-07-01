package fr.claudegateway.auth;

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
 * Token de réinitialisation de mot de passe, à usage unique et expirant, rattaché à un utilisateur.
 * La valeur ({@link #token}) est opaque (aléatoire) et n'est jamais réexposée par l'API.
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetToken {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token", nullable = false, unique = true, length = 255, updatable = false)
    private String token;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Vrai si le token n'a pas encore été consommé et n'est pas expiré à l'instant donné. */
    public boolean isUsableAt(OffsetDateTime now) {
        return usedAt == null && expiresAt.isAfter(now);
    }
}
