package fr.claudegateway.git;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.byok.ByokKeyCipher;
import fr.claudegateway.byok.EncryptedKey;
import fr.claudegateway.git.dto.GitTokenStatusResponse;

/**
 * Gestion du jeton GitHub de l'utilisateur courant (F-31 / SF-31-01) : enregistrement (vérification
 * auprès de GitHub <b>puis</b> chiffrement), état masqué et retrait.
 *
 * <p><b>Isolation multi-tenant</b> : chaque opération porte exclusivement sur le {@code userId}
 * fourni par l'appelant (issu du {@code SecurityContext}). Le jeton en clair n'est jamais journalisé,
 * jamais persisté et jamais renvoyé : seuls le compte GitHub et {@code …last4} sortent du service.</p>
 *
 * <p><b>Ordre volontaire</b> : rien n'est écrit tant que GitHub n'a pas accepté le jeton. Un jeton
 * invalide stocké ne se manifesterait qu'au premier clone, loin du geste qui l'a causé — et un
 * jeton précédent valide serait perdu pour rien.</p>
 */
@Service
public class GitTokenService {

    private static final Logger log = LoggerFactory.getLogger(GitTokenService.class);

    private static final int LAST4 = 4;
    private static final int MAX_TOKEN_LENGTH = 255;

    private final UserGitCredentialRepository repository;
    private final ByokKeyCipher cipher;
    private final GitHubClient gitHubClient;
    private final ApplicationEventPublisher events;

    public GitTokenService(UserGitCredentialRepository repository, ByokKeyCipher cipher,
            GitHubClient gitHubClient, ApplicationEventPublisher events) {
        this.repository = repository;
        this.cipher = cipher;
        this.gitHubClient = gitHubClient;
        this.events = events;
    }

    /** État du jeton de l'utilisateur (présent/absent, masqué). */
    @Transactional(readOnly = true)
    public GitTokenStatusResponse getStatus(UUID userId) {
        return repository.findByUserId(userId)
                .map(GitTokenService::toStatus)
                .orElseGet(GitTokenStatusResponse::absent);
    }

    /**
     * Vérifie le jeton auprès de GitHub puis l'enregistre chiffré. Upsert : remplace le jeton
     * existant de l'utilisateur (au plus un jeton par {@code user_id}).
     *
     * @throws InvalidGitTokenException                    format vide/trop long, ou jeton refusé par GitHub
     * @throws GitHubUnavailableException                  GitHub injoignable (échec temporaire, 503)
     * @throws fr.claudegateway.byok.ByokDisabledException chiffrement non configuré (503) — jamais
     *                                                     de repli en clair
     */
    @Transactional
    public GitTokenStatusResponse saveToken(UUID userId, String rawToken) {
        String token = rawToken == null ? "" : rawToken.trim();
        validateFormat(token);

        // Vérification AVANT toute écriture : un jeton refusé laisse l'état précédent intact.
        GitHubAccount account = gitHubClient.verifyToken(token);

        EncryptedKey encrypted = cipher.encrypt(token);

        UserGitCredential credential = repository.findByUserId(userId)
                .orElseGet(() -> UserGitCredential.builder().userId(userId).build());
        // Le vault du fournisseur porte l'ANCIEN jeton : il ne vaut plus rien et ne doit pas survivre
        // au remplacement. La référence est effacée ici, la destruction est demandée après commit.
        GitVaultRevokedEvent revoked = detachVault(credential);
        credential.setGithubLogin(account.login());
        credential.setEncryptedDataKey(encrypted.encryptedDataKey());
        credential.setCipherIv(encrypted.iv());
        credential.setCiphertext(encrypted.ciphertext());
        credential.setTokenLast4(last4(token));

        UserGitCredential saved = repository.save(credential);
        publish(revoked);
        log.info("Jeton GitHub enregistré pour l'utilisateur {}", userId);
        return toStatus(saved);
    }

    /**
     * Vault de credentials associé au jeton de l'utilisateur (F-31 / SF-31-05), s'il en existe un.
     * Isolation {@code user_id}. Ne renvoie que des identifiants opaques, jamais de secret.
     */
    @Transactional(readOnly = true)
    public Optional<GitVaultRef> findVault(UUID userId) {
        return repository.findByUserId(userId)
                .filter(credential -> credential.getMcpVaultId() != null)
                .map(credential -> new GitVaultRef(credential.getMcpVaultId(),
                        credential.getMcpCredentialId()));
    }

    /**
     * Mémorise le vault créé chez le fournisseur pour ce jeton (F-31 / SF-31-05), afin de le
     * réutiliser aux sessions suivantes plutôt que d'en créer un par session.
     *
     * <p>Sans effet si l'utilisateur n'a plus de jeton : le vault référencé n'aurait plus de raison
     * d'être, et le rattacher à rien laisserait un identifiant orphelin en base.</p>
     */
    @Transactional
    public void rememberVault(UUID userId, GitVaultRef vault) {
        repository.findByUserId(userId).ifPresent(credential -> {
            credential.setMcpVaultId(vault.vaultId());
            credential.setMcpCredentialId(vault.credentialId());
            repository.save(credential);
        });
    }

    /**
     * Résout le jeton GitHub de l'utilisateur pour un usage interne (montage d'un dépôt, SF-31-02) :
     * déchiffré à la volée, jamais persisté ni journalisé. Vide si aucun jeton. Isolation
     * {@code user_id}.
     */
    @Transactional(readOnly = true)
    public Optional<String> resolveToken(UUID userId) {
        return repository.findByUserId(userId)
                .map(credential -> cipher.decrypt(new EncryptedKey(
                        credential.getEncryptedDataKey(),
                        credential.getCipherIv(),
                        credential.getCiphertext())));
    }

    /**
     * Retire le jeton de l'utilisateur (idempotent : aucun effet, aucune erreur, s'il est absent).
     *
     * <p>La copie déposée chez le fournisseur d'agents part avec lui : un jeton révoqué ici mais
     * toujours utilisable là-bas serait une révocation de façade.</p>
     */
    @Transactional
    public void deleteToken(UUID userId) {
        GitVaultRevokedEvent revoked = repository.findByUserId(userId)
                .map(GitTokenService::toRevokedEvent)
                .orElse(null);
        repository.deleteByUserId(userId);
        publish(revoked);
        log.info("Jeton GitHub retiré pour l'utilisateur {}", userId);
    }

    /**
     * Détache le vault du jeton en place et décrit sa destruction, ou {@code null} s'il n'y en avait
     * pas. L'entité est modifiée ici, l'appel au fournisseur n'aura lieu qu'après commit.
     */
    private static GitVaultRevokedEvent detachVault(UserGitCredential credential) {
        GitVaultRevokedEvent revoked = toRevokedEvent(credential);
        credential.setMcpVaultId(null);
        credential.setMcpCredentialId(null);
        return revoked;
    }

    private static GitVaultRevokedEvent toRevokedEvent(UserGitCredential credential) {
        if (credential.getMcpVaultId() == null) {
            return null;
        }
        return new GitVaultRevokedEvent(credential.getUserId(), credential.getMcpVaultId(),
                credential.getMcpCredentialId());
    }

    private void publish(GitVaultRevokedEvent revoked) {
        if (revoked != null) {
            events.publishEvent(revoked);
        }
    }

    private static void validateFormat(String token) {
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            // Aucun appel réseau, aucune écriture : le jeton n'a même pas quitté le service.
            throw new InvalidGitTokenException("Jeton GitHub invalide.");
        }
    }

    /** 4 derniers caractères, ou le jeton entier s'il est plus court (aucun dépassement possible). */
    private static String last4(String token) {
        return token.length() <= LAST4 ? token : token.substring(token.length() - LAST4);
    }

    private static GitTokenStatusResponse toStatus(UserGitCredential credential) {
        return new GitTokenStatusResponse(
                true,
                credential.getGithubLogin(),
                "…" + credential.getTokenLast4(),
                credential.getTokenLast4(),
                credential.getCreatedAt(),
                credential.getUpdatedAt());
    }
}
