package fr.claudegateway.activity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des CRA déclarés (F-124 / SF-124-02). Toute lecture filtre {@code user_id} : un CRA
 * n'est visible que par son propriétaire. Aucune logique métier ici.
 */
@Repository
public interface CraEntryRepository extends JpaRepository<CraEntry, UUID> {

    /** Tous les CRA déclarés d'un utilisateur — l'entrée du calcul du cumul. */
    List<CraEntry> findByUserId(UUID userId);

    /** Les CRA déclarés d'un poste possédé. */
    List<CraEntry> findByUserIdAndHostId(UUID userId, UUID hostId);

    /** Le CRA déclaré d'un poste pour un mois, s'il existe (unicité `(user_id, host_id, year_month)`). */
    Optional<CraEntry> findByUserIdAndHostIdAndYearMonth(UUID userId, UUID hostId, String yearMonth);
}
