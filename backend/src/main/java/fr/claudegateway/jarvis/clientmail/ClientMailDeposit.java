package fr.claudegateway.jarvis.clientmail;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Un dépôt</b> (F-249 / SF-249-01, D5) — l'empreinte SHA-256 d'un fichier reçu d'un poste et son issue. Sert au
 * dédoublonnage (unique {@code user_id, host_id, content_hash}), au rappel du soir et au récapitulatif. Aucun nom de
 * fichier, aucun contenu. Purge à 30 jours.
 */
@Entity
@Table(name = "jarvis_mail_deposits")
@Getter
@Setter
@NoArgsConstructor
public class ClientMailDeposit {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String IGNORED = "IGNORED";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "content_hash", nullable = false, updatable = false, length = 64)
    private String contentHash;

    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "deposited_at", nullable = false)
    private OffsetDateTime depositedAt;
}
