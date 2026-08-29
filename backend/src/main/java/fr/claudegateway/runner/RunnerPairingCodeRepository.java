package fr.claudegateway.runner;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistance des codes d'appairage runner (F-38 / SF-38-01). Aucune logique métier ici. */
@Repository
public interface RunnerPairingCodeRepository extends JpaRepository<RunnerPairingCode, UUID> {

    Optional<RunnerPairingCode> findByCodeHash(String codeHash);
}
