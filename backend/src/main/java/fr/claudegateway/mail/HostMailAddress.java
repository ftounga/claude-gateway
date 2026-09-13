package fr.claudegateway.mail;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
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
 * <b>L'adresse de réception d'un client</b> (F-110 / SF-110-01) : là où l'utilisateur reçoit, pour ce
 * poste, les courriels qu'il s'envoie.
 *
 * <p>Elle ne sert qu'une fois <b>vérifiée</b> ({@link #verifiedAt} non nul) : une faute de frappe enverrait
 * sinon des données du client à un inconnu. Le code de vérification n'est jamais conservé en clair —
 * seulement son empreinte SHA-256.</p>
 */
@Entity
@Table(name = "host_mail_addresses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostMailAddress {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire. Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Poste (client) auquel l'adresse est rattachée. */
    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Adresse normalisée (minuscules), vérifiée ou non. */
    @Column(name = "address", nullable = false, length = MailAddresses.MAX_LENGTH)
    private String address;

    /** Instant de la vérification ; nul tant que le code n'a pas été saisi. */
    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    /** Empreinte SHA-256 (hex) du code en attente ; nulle hors attente. */
    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "code_expires_at")
    private OffsetDateTime codeExpiresAt;

    @Column(name = "code_sent_at")
    private OffsetDateTime codeSentAt;

    @Column(name = "code_attempts", nullable = false)
    private int codeAttempts;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Vrai si l'adresse peut recevoir. */
    public boolean isVerified() {
        return verifiedAt != null;
    }
}
