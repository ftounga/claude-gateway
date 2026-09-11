package fr.claudegateway.billing.seat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
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
 * <b>Mois-poste</b> (F-65 / SF-65-01) : « ce poste a été facturable pendant cette période, à partir
 * de cette date ».
 *
 * <p>L'état de mission de F-60 dit quels postes sont facturables <b>maintenant</b> ; cette ligne
 * dit ce qui a été vrai <b>pendant le mois</b>, et c'est tout ce qu'elle fait. Sans elle, deux
 * comportements seraient impossibles à tenir :</p>
 * <ul>
 *   <li>un poste <b>clôturé</b> en cours de mois disparaîtrait du mois qu'il a déjà engagé — et
 *       clôturer chaque 30 du mois deviendrait une méthode ;</li>
 *   <li>un poste <b>rouvert</b> dans le même mois serait compté deux fois — le piège à utilisateur
 *       que le cadrage refuse.</li>
 * </ul>
 *
 * <p><b>Écrite une fois, jamais réécrite.</b> L'unicité {@code (host_id, period_start)} porte la
 * règle « un mois-poste se paie une fois » : la réouverture retrouve la ligne et la laisse telle
 * quelle. Seules les purges l'effacent (suppression du poste, suppression du compte).</p>
 *
 * <p><b>L'absence de ligne est signifiante</b> : un poste facturable aujourd'hui sans ligne sur la
 * période l'est depuis le premier jour du mois — ou depuis sa création, si elle est plus tardive.
 * C'est ce qui dispense F-65 de toute écriture périodique : aucun job mensuel ne peut manquer,
 * puisqu'aucun n'existe.</p>
 */
@Entity
@Table(name = "host_seat_months")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostSeatMonth {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire (= {@code users.id}). Racine de l'isolation : toute lecture filtre dessus. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Poste concerné (= {@code runner_hosts.id}), sans clé étrangère (voir la migration 070). */
    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** Premier jour de la période (mois calendaire UTC), même définition de période que F-10. */
    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    /**
     * Jour à partir duquel le poste est facturable sur cette période. Une <b>date</b>, parce que la
     * proratisation est à la journée : une heure laisserait croire qu'on facture à l'heure.
     */
    @Column(name = "billable_from", nullable = false, updatable = false)
    private LocalDate billableFrom;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
