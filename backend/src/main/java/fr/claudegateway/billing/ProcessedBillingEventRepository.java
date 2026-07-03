package fr.claudegateway.billing;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accès au registre d'idempotence des événements de facturation (F-21 / SF-21-02). La présence d'une
 * ligne pour un {@code event_id} signifie que l'événement a déjà été traité (pas de re-crédit).
 */
public interface ProcessedBillingEventRepository extends JpaRepository<ProcessedBillingEvent, String> {
}
