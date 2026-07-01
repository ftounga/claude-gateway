package fr.claudegateway.billing;

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
 * Abonnement d'un utilisateur à la plateforme (F-09). Un seul abonnement par utilisateur
 * (contrainte d'unicité sur {@code user_id}), racine de l'isolation multi-tenant. Les identifiants
 * Stripe ({@link #stripeCustomerId}, {@link #stripeSubscriptionId}) sont <b>nullable</b> : ils ne
 * sont peuplés qu'après un paiement (SF-09-02) et ne sont <b>jamais</b> exposés au client.
 */
@Entity
@Table(name = "subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire, unique. */
    @Column(name = "user_id", nullable = false, updatable = false, unique = true)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SubscriptionStatus status;

    /** Plan payant souscrit ; {@code null} tant que l'utilisateur est en essai. */
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", length = 32)
    private PlanCode planCode;

    /** Fin de l'essai gratuit ; {@code null} si l'utilisateur n'est jamais passé par un essai. */
    @Column(name = "trial_ends_at")
    private OffsetDateTime trialEndsAt;

    /** Fin de la période de facturation courante (peuplé par le webhook Stripe, SF-09-02). */
    @Column(name = "current_period_end")
    private OffsetDateTime currentPeriodEnd;

    /** Identifiant client Stripe (interne, jamais exposé). Peuplé en SF-09-02. */
    @Column(name = "stripe_customer_id", length = 64)
    private String stripeCustomerId;

    /** Identifiant abonnement Stripe (interne, jamais exposé). Peuplé en SF-09-02. */
    @Column(name = "stripe_subscription_id", length = 64)
    private String stripeSubscriptionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
