package fr.claudegateway.activity;

import java.math.BigDecimal;
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
 * Un <b>CRA déclaré</b> (F-124 / SF-124-02) : les jours travaillés sur un poste pour un mois donné.
 *
 * <p><b>Seuls les CRA déclarés sont stockés.</b> Le « supposé » (mois complet automatique) est
 * calculé à la volée, sans aucune ligne — l'absence d'entrée est signifiante. Au plus une ligne par
 * {@code (user_id, host_id, year_month)} : renvoyer un CRA pour un mois <b>écrase</b> l'ancien
 * (chemin d'écriture en SF-124-03).</p>
 *
 * <p>{@link #days} est en {@code numeric(4,1)} : les <b>demi-journées</b> (0,5) sont admises.</p>
 */
@Entity
@Table(name = "cra_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CraEntry {

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

    /** Mois visé, {@code 'YYYY-MM'}. */
    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    /** Jours travaillés déclarés, demi-journées admises (0,5). */
    @Column(name = "days", nullable = false, precision = 4, scale = 1)
    private BigDecimal days;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
