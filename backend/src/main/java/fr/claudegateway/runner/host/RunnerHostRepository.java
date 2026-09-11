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

    /**
     * Postes d'un utilisateur dont la mission n'est <b>pas</b> dans l'état donné, les plus anciens
     * d'abord (F-65 / SF-65-01 : les postes <b>facturables</b>, c'est-à-dire tous sauf les clôturés).
     */
    List<RunnerHost> findByUserIdAndMissionStatusNotOrderByCreatedAtAsc(
            UUID userId, HostMissionStatus missionStatus);

    /** Lecture isolée : un poste n'est visible que par son propriétaire. */
    Optional<RunnerHost> findByIdAndUserId(UUID id, UUID userId);

    /** Purge à la suppression du compte : aucun poste ne survit à son propriétaire. */
    void deleteByUserId(UUID userId);
}
