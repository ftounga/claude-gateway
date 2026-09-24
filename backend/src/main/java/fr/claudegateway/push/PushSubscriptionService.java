package fr.claudegateway.push;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestion des abonnements Web Push (F-153 / SF-153-02) : s'abonner, se désabonner, servir la clé
 * publique VAPID. Toute opération est <b>scellée par {@code user_id}</b> (fourni par l'appelant
 * depuis le jeton, jamais depuis le corps de la requête).
 */
@Service
public class PushSubscriptionService {

    private final PushSubscriptionRepository repository;
    private final PushProperties properties;

    public PushSubscriptionService(PushSubscriptionRepository repository, PushProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Abonne (ou ré-abonne) un appareil du propriétaire. Idempotent : un même endpoint pour le même
     * utilisateur ne crée pas de doublon ; ses clés sont rafraîchies (elles peuvent tourner).
     */
    @Transactional
    public void subscribe(UUID userId, String endpoint, String p256dh, String auth) {
        // Ré-abonnement idempotent : si l'appareil est déjà abonné, on rafraîchit ses clés en place
        // (elles peuvent tourner) plutôt que de supprimer puis réinsérer — ce qui heurterait la
        // contrainte d'unicité (user_id, endpoint), Hibernate ordonnant l'INSERT avant le DELETE.
        PushSubscription subscription = repository.findByUserIdAndEndpoint(userId, endpoint)
                .orElseGet(() -> PushSubscription.builder()
                        .userId(userId)
                        .endpoint(endpoint)
                        .createdAt(OffsetDateTime.now())
                        .build());
        subscription.setP256dh(p256dh);
        subscription.setAuth(auth);
        repository.save(subscription);
    }

    /** Désabonne un appareil du propriétaire (idempotent : un endpoint inconnu ne fait rien). */
    @Transactional
    public void unsubscribe(UUID userId, String endpoint) {
        repository.deleteByUserIdAndEndpoint(userId, endpoint);
    }

    /**
     * La clé <b>publique</b> VAPID à servir au frontend, ou {@code null} si le Web Push n'est pas
     * configuré (la privée n'est jamais exposée).
     */
    public String vapidPublicKey() {
        return properties.isConfigured() ? properties.vapidPublicKey() : null;
    }
}
