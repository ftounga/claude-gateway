package fr.claudegateway.jarvis.budget;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Le plafond de Jarvis d'un compte</b> (F-244 / SF-244-03) : un montant mensuel global en euros, l'option « appliquer
 * aussi en BYOK », et le mois de la dernière alerte à 80 %. Une ligne par compte, clé = {@code user_id}.
 */
@Entity
@Table(name = "jarvis_budgets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisBudget {

    /** Propriétaire : toute lecture et toute écriture passent par lui. */
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "monthly_cap_eur", nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyCapEur;

    @Builder.Default
    @Column(name = "apply_on_byok", nullable = false)
    private boolean applyOnByok = false;

    /** {@code YYYY-MM} du mois où l'alerte 80 % est partie, ou {@code null}. */
    @Column(name = "alert_80_sent_month", length = 7)
    private String alert80SentMonth;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
