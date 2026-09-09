package fr.claudegateway.runner;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistance des codes d'appairage runner (F-38 / SF-38-01). Aucune logique métier ici. */
@Repository
public interface RunnerPairingCodeRepository extends JpaRepository<RunnerPairingCode, UUID> {

    Optional<RunnerPairingCode> findByCodeHash(String codeHash);

    /** Purge à la suppression du compte (SF-38-14) : aucun code ne survit à son propriétaire. */
    void deleteByUserId(UUID userId);

    /** Purge à la suppression d'un poste (F-48) : aucun code ne survit à sa machine. */
    void deleteByHostId(UUID hostId);
}
