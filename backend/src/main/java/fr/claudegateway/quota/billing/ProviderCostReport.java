package fr.claudegateway.quota.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * <b>Une ligne de la facture du fournisseur</b> (F-224 / SF-224-02, migration 181) : le montant facturé d'un jour
 * ({@link #COST}), ou la consommation d'un jour d'une clé API chiffrée à la grille ({@link #KEY}). Donnée de la
 * PLATEFORME (la facture de l'organisation) — lue par l'administrateur seul, aucune donnée d'un compte client.
 */
@Entity
@Table(name = "provider_cost_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProviderCostReport {

    /** Le montant facturé du jour, toutes clés confondues. */
    public static final String COST = "COST";

    /** La consommation du jour d'une clé API. */
    public static final String KEY = "KEY";

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "report_day", nullable = false)
    private LocalDate day;

    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    /** L'identifiant de la clé ({@link #KEY}) ; chaîne vide pour {@link #COST}. */
    @Column(name = "api_key_id", nullable = false, length = 80)
    private String apiKeyId;

    @Column(name = "api_key_label", length = 120)
    private String apiKeyLabel;

    @Column(name = "amount_usd", nullable = false, precision = 16, scale = 6)
    private BigDecimal amountUsd;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;
}
