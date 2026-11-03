package fr.claudegateway.jarvis.mail;

import java.util.UUID;

/**
 * Ce qui dépend d'une boîte et part avec elle (F-242) : les courriers en cache (SF-242-02), les actions en attente
 * (SF-242-06). Chaque implémentation filtre sur {@code user_id}.
 */
public interface JarvisMailPurge {

    /** « Révoquer » une boîte. */
    void purgeAccount(UUID userId, UUID accountId);

    /** Suppression du compte. */
    void purgeUser(UUID userId);
}
