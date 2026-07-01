package fr.claudegateway.byok;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatMessage;
import fr.claudegateway.ai.ChatRole;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.byok.dto.ApiKeyStatusResponse;

/**
 * Gestion de la clé API personnelle (BYOK, F-03) de l'utilisateur courant : ajout (validation par
 * appel test réel puis chiffrement), statut masqué et suppression.
 *
 * <p>Isolation multi-tenant : chaque opération porte exclusivement sur le {@code userId} fourni par
 * l'appelant (issu du {@code SecurityContext}). La clé en clair n'est jamais journalisée, ni stockée,
 * ni renvoyée : seule sa version masquée ({@code sk-…last4}) sort du service.</p>
 */
@Service
public class ByokKeyService {

    private static final Logger log = LoggerFactory.getLogger(ByokKeyService.class);

    private static final String KEY_PREFIX = "sk-";
    private static final int MIN_KEY_LENGTH = 8;
    private static final int LAST4 = 4;

    private final UserApiKeyRepository repository;
    private final ByokKeyCipher cipher;
    private final AIProvider aiProvider;
    private final ModelCatalog modelCatalog;

    public ByokKeyService(UserApiKeyRepository repository, ByokKeyCipher cipher,
            AIProvider aiProvider, ModelCatalog modelCatalog) {
        this.repository = repository;
        this.cipher = cipher;
        this.aiProvider = aiProvider;
        this.modelCatalog = modelCatalog;
    }

    /** Statut de la clé de l'utilisateur (présente/absente, masquée). */
    @Transactional(readOnly = true)
    public ApiKeyStatusResponse getStatus(UUID userId) {
        return repository.findByUserId(userId)
                .map(ByokKeyService::toStatus)
                .orElseGet(ApiKeyStatusResponse::absent);
    }

    /**
     * Valide puis enregistre (chiffrée) la clé de l'utilisateur. Upsert : remplace la clé existante.
     *
     * @throws InvalidApiKeyException si le format est invalide ou si le fournisseur refuse la clé
     * @throws ByokDisabledException  si le chiffrement BYOK n'est pas configuré (503)
     */
    @Transactional
    public ApiKeyStatusResponse saveKey(UUID userId, String rawApiKey) {
        String apiKey = rawApiKey == null ? "" : rawApiKey.trim();
        validateFormat(apiKey);
        // Appel test réel : rien n'est persisté tant que le fournisseur n'a pas accepté la clé.
        validateWithProvider(apiKey);

        EncryptedKey encrypted = cipher.encrypt(apiKey);
        String last4 = apiKey.substring(apiKey.length() - LAST4);

        UserApiKey entity = repository.findByUserId(userId)
                .orElseGet(() -> UserApiKey.builder().userId(userId).build());
        entity.setProvider(ByokProvider.ANTHROPIC);
        entity.setEncryptedDataKey(encrypted.encryptedDataKey());
        entity.setCipherIv(encrypted.iv());
        entity.setCiphertext(encrypted.ciphertext());
        entity.setKeyLast4(last4);
        entity.setActive(true);
        entity.setValidatedAt(OffsetDateTime.now());

        UserApiKey saved = repository.save(entity);
        log.info("Clé BYOK enregistrée pour l'utilisateur {} (mode BYOK actif)", userId);
        return toStatus(saved);
    }

    /**
     * Bascule le mode fournisseur de l'utilisateur (F-03 / SF-03-03).
     *
     * @param mode {@code BYOK} (requiert une clé) ou {@code HOSTED}
     * @throws ByokModeException si {@code BYOK} est demandé sans clé enregistrée
     */
    @Transactional
    public ApiKeyStatusResponse setMode(UUID userId, String mode) {
        boolean enableByok = "BYOK".equalsIgnoreCase(mode);
        UserApiKey key = repository.findByUserId(userId).orElse(null);
        if (enableByok) {
            if (key == null) {
                throw new ByokModeException("Aucune clé BYOK enregistrée : ajoutez une clé avant d'activer le mode BYOK.");
            }
            key.setActive(true);
            return toStatus(repository.save(key));
        }
        // HOSTED : la clé (si présente) est conservée mais désactivée.
        if (key == null) {
            return ApiKeyStatusResponse.absent();
        }
        key.setActive(false);
        return toStatus(repository.save(key));
    }

    /**
     * Résout la clé BYOK <b>active</b> de l'utilisateur pour un appel fournisseur : la clé est
     * déchiffrée à la volée (jamais persistée ni journalisée). Vide si aucune clé active
     * (l'appelant retombe alors sur la clé plateforme, mode Hosted). Isolation {@code user_id}.
     */
    @Transactional(readOnly = true)
    public Optional<String> resolveActiveApiKey(UUID userId) {
        return repository.findByUserId(userId)
                .filter(UserApiKey::isActive)
                .map(key -> cipher.decrypt(new EncryptedKey(
                        key.getEncryptedDataKey(), key.getCipherIv(), key.getCiphertext())));
    }

    /** Supprime la clé de l'utilisateur (idempotent). */
    @Transactional
    public void deleteKey(UUID userId) {
        repository.deleteByUserId(userId);
        log.info("Clé BYOK supprimée pour l'utilisateur {}", userId);
    }

    private void validateFormat(String apiKey) {
        if (!apiKey.startsWith(KEY_PREFIX) || apiKey.length() < MIN_KEY_LENGTH) {
            throw new InvalidApiKeyException("Format de clé API invalide.");
        }
    }

    /**
     * Valide la clé par un appel test réel au fournisseur (mode BYOK : la clé est passée en paramètre
     * neutre, jamais journalisée). Tout échec fournisseur est traité comme une clé invalide (rien
     * n'est persisté).
     */
    private void validateWithProvider(String apiKey) {
        try {
            aiProvider.complete(new ChatCompletionRequest(
                    modelCatalog.defaultModel(),
                    List.of(new ChatMessage(ChatRole.USER, "ping")),
                    List.of(),
                    apiKey));
        } catch (AIProviderException ex) {
            // Ni la clé ni la réponse brute du fournisseur ne sont journalisées.
            log.info("Validation BYOK échouée : la clé a été refusée par le fournisseur");
            throw new InvalidApiKeyException("La clé API n'a pas pu être validée par le fournisseur.");
        }
    }

    private static ApiKeyStatusResponse toStatus(UserApiKey key) {
        String mode = key.isActive() ? "BYOK" : "HOSTED";
        return new ApiKeyStatusResponse(
                true,
                KEY_PREFIX + "…" + key.getKeyLast4(),
                key.getKeyLast4(),
                key.getProvider().name(),
                mode,
                key.getValidatedAt(),
                key.getCreatedAt());
    }
}
