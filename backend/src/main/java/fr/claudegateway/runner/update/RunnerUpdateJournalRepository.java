package fr.claudegateway.runner.update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistance du journal des mises à jour du runner (F-111 / SF-111-04). Aucune logique ici. */
@Repository
public interface RunnerUpdateJournalRepository extends JpaRepository<RunnerUpdateJournalEntry, UUID> {

    /** La dernière mise à jour d'un poste. */
    Optional<RunnerUpdateJournalEntry> findFirstByHostIdOrderByRequestedAtDesc(UUID hostId);

    /** Les 20 dernières mises à jour d'un poste, les plus récentes d'abord. */
    List<RunnerUpdateJournalEntry> findTop20ByHostIdOrderByRequestedAtDesc(UUID hostId);

    /** Une mise à jour précise d'un poste : l'identifiant d'une trame ne vaut que pour SON poste. */
    Optional<RunnerUpdateJournalEntry> findByIdAndHostId(UUID id, UUID hostId);
}
