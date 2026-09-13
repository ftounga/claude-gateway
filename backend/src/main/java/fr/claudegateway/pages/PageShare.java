package fr.claudegateway.pages;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Un lien de partage d'une page</b> (F-109 / SF-109-05). Seule l'empreinte du jeton est conservée ; rien du
 * visiteur n'est écrit, hormis le nombre d'ouvertures et la date de la dernière.
 */
@Entity
@Table(name = "page_shares")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageShare {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "page_id", nullable = false, updatable = false)
    private UUID pageId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "open_count", nullable = false)
    private long openCount;

    @Column(name = "last_opened_at")
    private OffsetDateTime lastOpenedAt;
}
