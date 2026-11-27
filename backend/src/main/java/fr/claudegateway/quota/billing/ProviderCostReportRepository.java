package fr.claudegateway.quota.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** La facture du fournisseur, jour par jour (F-224 / SF-224-02) — donnée de plateforme, lue par l'administrateur. */
public interface ProviderCostReportRepository extends JpaRepository<ProviderCostReport, UUID> {

    Optional<ProviderCostReport> findByDayAndKindAndApiKeyId(LocalDate day, String kind, String apiKeyId);

    List<ProviderCostReport> findByDayGreaterThanEqualAndDayLessThanOrderByDayAsc(LocalDate from, LocalDate to);

    Optional<ProviderCostReport> findFirstByOrderByFetchedAtDesc();

    /** La dernière relecture d'une famille de lignes (F-279 : Anthropic {@code COST}/{@code KEY}, ou {@code BEDROCK}). */
    Optional<ProviderCostReport> findFirstByKindInOrderByFetchedAtDesc(java.util.Collection<String> kinds);

    /** Les lignes d'une famille sur {@code [from, to)} (F-279 / SF-279-01). */
    List<ProviderCostReport> findByKindAndDayGreaterThanEqualAndDayLessThanOrderByDayAsc(String kind, LocalDate from,
            LocalDate to);
}
