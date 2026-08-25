package fr.claudegateway.git;

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
 * Jeton d'accès GitHub d'un utilisateur (F-31 / SF-31-01), <b>chiffré au repos</b> par enveloppe —
 * exactement le mécanisme de la clé BYOK (F-03), réutilisé tel quel ; seule la nature du secret
 * change.
 *
 * <p>Aucune donnée en clair : seuls le blob chiffré ({@link #encryptedDataKey}, {@link #cipherIv},
 * {@link #ciphertext}), les 4 derniers caractères ({@link #tokenLast4}) et le compte GitHub associé
 * ({@link #githubLogin}, information publique) sont persistés. Le jeton en clair n'existe qu'en
 * mémoire, le temps d'un chiffrement/déchiffrement, et n'est jamais journalisé.</p>
 *
 * <p>Table dédiée : le stockage de la clé Claude ({@code user_api_keys}) n'est pas réutilisé, sa
 * contrainte d'unicité sur {@code user_id} portant déjà un autre secret en production.</p>
 */
@Entity
@Table(name = "user_git_credentials")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserGitCredential {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Racine d'isolation multi-tenant : tout accès filtre sur ce champ. Unique (1 jeton/utilisateur). */
    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    /** Compte GitHub auquel le jeton donne accès, tel que renvoyé par la vérification. Public. */
    @Column(name = "github_login", length = 100)
    private String githubLogin;

    /** Data key chiffrée (base64) — CiphertextBlob KMS ou wrap local. */
    @Column(name = "encrypted_data_key", nullable = false, length = 1024)
    private String encryptedDataKey;

    /** IV AES-GCM du chiffrement du jeton (base64). */
    @Column(name = "cipher_iv", nullable = false, length = 1024)
    private String cipherIv;

    /** Jeton chiffré en AES-GCM, tag inclus (base64). */
    @Column(name = "ciphertext", nullable = false, length = 1024)
    private String ciphertext;

    /** 4 derniers caractères du jeton, pour affichage masqué ({@code …last4}). */
    @Column(name = "token_last4", nullable = false, length = 4)
    private String tokenLast4;

    /**
     * Identifiant du <b>vault</b> de credentials créé chez le fournisseur d'agents pour cet
     * utilisateur (F-31 / SF-31-05), ou {@code null} tant qu'aucun n'a été créé — il l'est
     * paresseusement, à la première session sur un dépôt Git.
     *
     * <p>Ce n'est <b>pas un secret</b> : un identifiant opaque ({@code vlt_…}). Le jeton déposé dans
     * ce vault est write-only côté fournisseur — jamais relu, jamais renvoyé.</p>
     */
    @Column(name = "mcp_vault_id", length = 64)
    private String mcpVaultId;

    /** Identifiant de la credential déposée dans ce vault ({@code vcrd_…}). Pas un secret non plus. */
    @Column(name = "mcp_credential_id", length = 64)
    private String mcpCredentialId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
