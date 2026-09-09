package fr.claudegateway.runner.host;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des postes (F-48 / SF-48-01). Toute lecture propre à un utilisateur filtre sur
 * {@code user_id} (isolation multi-tenant). Aucune logique métier ici.
 */
@Repository
public interface RunnerHostRepository extends JpaRepository<RunnerHost, UUID> {

    /** Postes d'un utilisateur, les plus récents d'abord. */
    List<RunnerHost> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Lecture isolée : un poste n'est visible que par son propriétaire. */
    Optional<RunnerHost> findByIdAndUserId(UUID id, UUID userId);

    /** Purge à la suppression du compte : aucun poste ne survit à son propriétaire. */
    void deleteByUserId(UUID userId);
}
