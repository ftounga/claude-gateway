package fr.claudegateway.push;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 * Un <b>abonnement Web Push</b> (F-153 / SF-153-02) : un appareil du propriétaire qui a accepté de
 * recevoir une notification système (tour terminé / autorisation demandée), même l'application
 * fermée.
 *
 * <p><b>Scellé par {@link #userId}.</b> Toute lecture filtre le propriétaire : on ne notifie
 * <b>que</b> les appareils du compte. La ligne ne survit pas à son compte (purge explicite dans
 * {@code AccountService.deleteAccount}).</p>
 *
 * <p><b>Ce que porte la ligne</b> : l'{@link #endpoint} du service push du navigateur et les deux
 * clés cliente ({@link #p256dh}, {@link #auth}) du protocole Web Push (RFC 8291). Aucune donnée
 * sensible, aucun contenu de tour. Ce ne sont <b>pas</b> des secrets de la gateway : la clé VAPID
 * (privée) vit en secret d'environnement, jamais en base.</p>
 */
@Entity
@Table(name = "push_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushSubscription {

    /** Longueurs maximales acceptées (alignées sur la migration 130). */
    public static final int MAX_ENDPOINT_LENGTH = 2000;
    public static final int MAX_KEY_LENGTH = 255;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Racine de l'isolation : aucune lecture sans ce filtre. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** URL du service push du navigateur (l'adresse à qui pousser). */
    @Column(name = "endpoint", nullable = false, length = MAX_ENDPOINT_LENGTH)
    private String endpoint;

    /** Clé publique cliente (base64url) — chiffrement de la charge (RFC 8291). */
    @Column(name = "p256dh", nullable = false, length = MAX_KEY_LENGTH)
    private String p256dh;

    /** Secret d'authentification cliente (base64url). */
    @Column(name = "auth", nullable = false, length = MAX_KEY_LENGTH)
    private String auth;

    /** Instant de l'abonnement. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
