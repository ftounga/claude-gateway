package fr.claudegateway.activity;

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
 * Le <b>TJM</b> d'un poste (F-124 / SF-124-01) : le taux journalier (€ HT/jour) que l'utilisateur
 * facture au client installé sur cette machine.
 *
 * <p>Au plus une ligne par {@code (user_id, host_id)}. Le montant est tenu en <b>centimes</b>
 * ({@link #dailyRateCents}) — jamais un flottant en base ; le calcul du cumul (SF-124-02) arrondit
 * {@code jours × TJM} au centime.</p>
 *
 * <p><b>Isolation</b> : {@link #userId} est la racine de l'isolation, {@link #hostId} désigne le
 * poste possédé. Rien n'est partagé entre comptes.</p>
 */
@Entity
@Table(name = "poste_billing")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PosteBilling {

    /** Borne haute du TJM : 1 000 000 € HT/jour, en centimes. Au-delà, c'est une faute de saisie. */
    public static final long MAX_DAILY_RATE_CENTS = 100_000_000L;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Poste concerné (= {@code runner_hosts.id}), possédé par {@link #userId}. */
    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** TJM en centimes d'euro HT. Jamais négatif, borné par {@link #MAX_DAILY_RATE_CENTS}. */
    @Column(name = "daily_rate_cents", nullable = false)
    private long dailyRateCents;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
