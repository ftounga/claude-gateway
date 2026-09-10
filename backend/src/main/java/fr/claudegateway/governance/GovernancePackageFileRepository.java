package fr.claudegateway.governance;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Accès aux fichiers apportés par un paquet (F-51 / SF-51-01). */
public interface GovernancePackageFileRepository extends JpaRepository<GovernancePackageFile, UUID> {

    /** Les fichiers d'un paquet, dans l'ordre de rédaction. */
    List<GovernancePackageFile> findByPackageIdOrderByPositionAsc(UUID packageId);

    /** Les fichiers de plusieurs paquets, en une lecture (catalogue, aperçu, dépôt). */
    List<GovernancePackageFile> findByPackageIdInOrderByPositionAsc(List<UUID> packageIds);

    /** Remplacement intégral du contenu d'un paquet : on efface avant de réécrire. */
    void deleteByPackageId(UUID packageId);
}
