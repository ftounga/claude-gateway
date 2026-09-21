package fr.claudegateway.governance.map;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance de la copie de travail des cartes (F-136 / SF-136-01).
 *
 * <p><b>Aucune lecture sans le couple utilisateur + poste.</b> C'est la garantie d'isolation de
 * cette table : la carte d'un client ne peut pas être servie au tour d'un autre, y compris entre
 * deux postes du même utilisateur.</p>
 */
@Repository
public interface HostMapFileRepository extends JpaRepository<HostMapFile, UUID> {

    /** La carte d'un poste, fichier par fichier, dans un ordre stable. */
    List<HostMapFile> findByUserIdAndHostIdOrderByPathAsc(UUID userId, UUID hostId);

    Optional<HostMapFile> findByUserIdAndHostIdAndPath(UUID userId, UUID hostId, String path);

    /** Purge à la suppression du compte : la copie ne survit pas à son propriétaire. */
    void deleteByUserId(UUID userId);

    /** Purge à la suppression d'un poste : la copie ne survit pas à la machine. */
    void deleteByUserIdAndHostId(UUID userId, UUID hostId);
}
