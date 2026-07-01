package fr.claudegateway.byok;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Clé API personnelle (BYOK, F-03) d'un utilisateur, <b>chiffrée au repos</b> (envelope encryption,
 * SF-03-01). Une seule ligne par utilisateur (contrainte d'unicité sur {@code user_id}).
 *
 * <p>Aucune donnée en clair : seuls le blob chiffré ({@link #encryptedDataKey}, {@link #cipherIv},
 * {@link #ciphertext}) et les 4 derniers caractères ({@link #keyLast4}) sont persistés. La clé en
 * clair n'existe qu'en mémoire, le temps d'un chiffrement/déchiffrement, et n'est jamais journalisée.</p>
 */
@Entity
@Table(name = "user_api_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserApiKey {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Racine d'isolation multi-tenant : tout accès filtre sur ce champ. Unique (1 clé/utilisateur). */
    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private ByokProvider provider;

    /** Data key chiffrée (base64) — CiphertextBlob KMS ou wrap local. */
    @Column(name = "encrypted_data_key", nullable = false, length = 1024)
    private String encryptedDataKey;

    /** IV AES-GCM du chiffrement de la clé API (base64). */
    @Column(name = "cipher_iv", nullable = false, length = 1024)
    private String cipherIv;

    /** Clé API chiffrée en AES-GCM, tag inclus (base64). */
    @Column(name = "ciphertext", nullable = false, length = 1024)
    private String ciphertext;

    /** 4 derniers caractères de la clé, pour affichage masqué ({@code sk-…last4}). */
    @Column(name = "key_last4", nullable = false, length = 4)
    private String keyLast4;

    /** Pilote la bascule Hosted/BYOK (SF-03-03) : {@code true} => clé utilisée pour le chat. */
    @Column(name = "active", nullable = false)
    private boolean active;

    /** Dernière validation réussie par appel test au fournisseur. */
    @Column(name = "validated_at")
    private OffsetDateTime validatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
