package fr.claudegateway.quota;

import java.time.LocalDate;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Compteur de consommation de tokens d'un utilisateur pour une période de facturation (F-10).
 *
 * <p>Une ligne par couple ({@code user_id}, {@code period_start}) — la période est le premier jour
 * du mois calendaire (UTC). Racine de l'isolation multi-tenant : tout accès filtre sur
 * {@code user_id}. Les compteurs sont cumulatifs et n'exposent aucune donnée sensible.</p>
 */
@Entity
@Table(name = "usage_counters",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_usage_counters_user_period",
                columnNames = {"user_id", "period_start"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageCounter {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Premier jour du mois de la période (UTC). Clé de période avec {@code user_id}. */
    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    /** Tokens d'entrée cumulés sur la période. */
    @Column(name = "input_tokens", nullable = false)
    @Builder.Default
    private long inputTokens = 0L;

    /** Tokens de sortie cumulés sur la période. */
    @Column(name = "output_tokens", nullable = false)
    @Builder.Default
    private long outputTokens = 0L;

    /** Tokens rachetés (top-up, F-21) crédités sur la période ; s'ajoutent au quota d'abonnement. */
    @Column(name = "bonus_tokens", nullable = false)
    @Builder.Default
    private long bonusTokens = 0L;

    /**
     * Temps de bac à sable Managed Agents cumulé sur la période (secondes, F-28 / SF-28-12). Somme
     * des {@code active_seconds} facturés des sessions d'exécution ; contrôlé contre un plafond.
     */
    @Column(name = "sandbox_seconds", nullable = false)
    @Builder.Default
    private long sandboxSeconds = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Total de tokens consommés sur la période (entrée + sortie). */
    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
