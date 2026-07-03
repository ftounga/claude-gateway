package fr.claudegateway.billing;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Registre d'idempotence des événements de facturation déjà traités (F-21 / SF-21-02). La clé primaire
 * est l'identifiant d'événement du fournisseur ({@code event_id}) : elle garantit qu'un rachat de tokens
 * (top-up) n'est crédité qu'une seule fois, même si le webhook est rejoué.
 *
 * <p>Ce registre est purement technique : aucune donnée utilisateur, aucun secret. Il n'est donc pas
 * filtré par {@code user_id} (contrairement aux entités métier) — sa clé est globale au fournisseur.</p>
 */
@Entity
@Table(name = "processed_billing_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedBillingEvent {

    /** Identifiant de l'événement fournisseur (ex. {@code evt_...}). Clé d'idempotence. */
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    /** Horodatage du premier traitement de l'événement. */
    @Column(name = "processed_at", nullable = false, updatable = false)
    private OffsetDateTime processedAt;
}
