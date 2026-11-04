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
 * <b>Un droit d'écriture dans un agenda Google</b> (F-243 / SF-243-03, D3) — le jeton de rafraîchissement OAuth (portée
 * {@code calendar.events}) chiffré par {@link fr.claudegateway.byok.ByokKeyCipher}, jamais renvoyé ni journalisé. Un seul
 * droit par compte et par adresse ; la révocation le révoque chez Google puis l'efface.
 */
@Entity
@Table(name = "jarvis_calendar_grants")
@Getter
@Setter
@NoArgsConstructor
public class JarvisCalendarGrant {

    /** Le type de client OAuth (D3, D4) : seul {@code INTERNAL} (Workspace) existe dans cette version. */
    public enum ClientKind {
        INTERNAL, EXTERNAL
    }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Racine d'isolation multi-tenant : tout accès filtre sur ce champ. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** L'adresse du compte Google relié (lue dans le jeton d'identité rendu par Google). */
    @Column(name = "account", nullable = false, length = 254)
    private String account;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_kind", nullable = false, length = 16)
    private ClientKind clientKind = ClientKind.INTERNAL;

    @Column(name = "encrypted_data_key", nullable = false, length = 1024)
    private String encryptedDataKey;

    @Column(name = "cipher_iv", nullable = false, length = 1024)
    private String cipherIv;

    @Column(name = "ciphertext", nullable = false, length = 4096)
    private String ciphertext;

    @Column(name = "scope", nullable = false, length = 500)
    private String scope;

    @Column(name = "granted_at", nullable = false)
    private OffsetDateTime grantedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;
}
