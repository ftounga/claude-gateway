package fr.claudegateway.jarvis.calendar;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Un agenda lu par Jarvis</b> (F-243 / SF-243-01, D1) — l'adresse iCal secrète est chiffrée par
 * {@link fr.claudegateway.byok.ByokKeyCipher} (patron {@code user_git_credentials}) ; seuls le libellé et les 4 derniers
 * caractères sortent du serveur. {@link #urlHash} (SHA-256 de l'adresse) sert à l'unicité par compte sans rien révéler.
 *
 * <p>Colonnes de relève (D2) : {@link #etag}/{@link #lastModified} pour la requête conditionnelle,
 * {@link #contentHash} pour reconnaître un corps inchangé, {@link #expandedAt} pour faire glisser la fenêtre, et le bail
 * ({@link #leaseUntil}) qui empêche deux pods de relever le même agenda.</p>
 */
@Entity
@Table(name = "jarvis_calendar_feeds")
@Getter
@Setter
@NoArgsConstructor
public class JarvisCalendarFeed {

    /** L'état d'un agenda. */
    public enum Status {
        /** La dernière lecture a réussi. */
        OK,
        /** Google répond 404 : l'adresse a été réinitialisée (D2). Une notification au plus. */
        BROKEN
    }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Racine d'isolation multi-tenant : tout accès filtre sur ce champ. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "label", nullable = false, length = 40)
    private String label;

    @Column(name = "url_hash", nullable = false, length = 64, updatable = false)
    private String urlHash;

    @Column(name = "encrypted_data_key", nullable = false, length = 1024)
    private String encryptedDataKey;

    @Column(name = "cipher_iv", nullable = false, length = 1024)
    private String cipherIv;

    @Column(name = "ciphertext", nullable = false, length = 4096)
    private String ciphertext;

    @Column(name = "secret_last4", nullable = false, length = 4)
    private String secretLast4;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.OK;

    @Column(name = "etag", length = 200)
    private String etag;

    @Column(name = "last_modified", length = 100)
    private String lastModified;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "event_count", nullable = false)
    private int eventCount;

    @Column(name = "last_ok_at")
    private OffsetDateTime lastOkAt;

    @Column(name = "expanded_at")
    private OffsetDateTime expandedAt;

    /** Message court et neutre (jamais l'adresse, jamais un contenu d'agenda). */
    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "next_poll_at")
    private OffsetDateTime nextPollAt;

    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
