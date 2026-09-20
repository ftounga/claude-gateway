package fr.claudegateway.quota;

import java.math.BigDecimal;
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
 * Budget hebdomadaire opposé à la dépense réelle d'un client (F-133 / SF-133-04).
 *
 * <p><b>Il n'arrête rien.</b> Aucun tour n'est refusé parce qu'un budget est dépassé : le refus de
 * service reste l'affaire du quota commercial (F-10 / F-36), qui a déjà ses plafonds, ses exceptions
 * et sa facturation. Mélanger les deux ferait d'un outil de pilotage interne un mécanisme de refus —
 * et un jour, un client serait coupé par un réglage interne qu'il n'a jamais vu.</p>
 *
 * <p><b>Deux portées, une seule table</b> : {@code host_id} à {@code null} est le budget
 * <b>par défaut</b>, celui qui s'applique à tout client qui n'en a pas de propre. Une seconde table
 * pour le défaut aurait dédoublé les lectures et les validations sans rien clarifier.</p>
 */
@Entity
@Table(name = "cost_budgets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CostBudget {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire. Racine de l'isolation : toute lecture et toute écriture filtrent dessus. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * Client concerné (= {@code runner_hosts.id}), ou {@code null} pour le budget <b>par défaut</b>.
     *
     * <p>Volontairement <b>sans clé étrangère</b>, comme {@code usage_turns} : ranger un poste ne
     * doit pas faire disparaître silencieusement la consigne de dépense qui le visait.</p>
     */
    @Column(name = "host_id", updatable = false)
    private UUID hostId;

    /** Montant hebdomadaire, en euros. Jamais négatif ; zéro est une consigne légitime. */
    @Column(name = "amount_eur", nullable = false, precision = 10, scale = 2)
    private BigDecimal amountEur;

    /** Dernière modification (horloge applicative). Jamais fournie par un client. */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
