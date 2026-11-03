package fr.claudegateway.jarvis.mail;

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
 * <b>Une boîte mail reliée à Jarvis</b> (F-242 / SF-242-01, D1) — patron {@code user_git_credentials} : le mot de passe
 * d'application est chiffré par {@link fr.claudegateway.byok.ByokKeyCipher} (trois colonnes base64), seuls les quatre
 * derniers caractères sont lisibles. Le clair n'existe qu'en mémoire, le temps d'une connexion, jamais journalisé.
 *
 * <p>Les colonnes de relève ({@link #inboxUidvalidity}, {@link #inboxLastUid}, {@link #nextPollAt},
 * {@link #leaseUntil}, {@link #authFailures}) servent au worker de SF-242-02.</p>
 */
@Entity
@Table(name = "jarvis_mail_accounts")
@Getter
@Setter
@NoArgsConstructor
public class JarvisMailAccount {

    /** L'état d'une boîte. */
    public enum Status {
        /** La dernière connexion a réussi. */
        OK,
        /** Google refuse le mot de passe : relève suspendue (retard), une notification au plus. */
        AUTH_FAILED,
        /** Relève mise en pause. */
        PAUSED
    }

    /** Le fournisseur (préréglage D2) ; un seul dans cette version. */
    public enum Provider {
        GOOGLE
    }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Racine d'isolation multi-tenant : tout accès filtre sur ce champ. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "address", nullable = false, length = 254, updatable = false)
    private String address;

    @Column(name = "label", nullable = false, length = 40)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private Provider provider = Provider.GOOGLE;

    @Column(name = "encrypted_data_key", nullable = false, length = 1024)
    private String encryptedDataKey;

    @Column(name = "cipher_iv", nullable = false, length = 1024)
    private String cipherIv;

    @Column(name = "ciphertext", nullable = false, length = 1024)
    private String ciphertext;

    @Column(name = "secret_last4", nullable = false, length = 4)
    private String secretLast4;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.OK;

    @Column(name = "last_ok_at")
    private OffsetDateTime lastOkAt;

    /** Message court et neutre (jamais un contenu de mail, jamais le secret). */
    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "auth_failures", nullable = false)
    private int authFailures;

    @Column(name = "inbox_uidvalidity")
    private Long inboxUidvalidity;

    @Column(name = "inbox_last_uid")
    private Long inboxLastUid;

    @Column(name = "next_poll_at")
    private OffsetDateTime nextPollAt;

    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
