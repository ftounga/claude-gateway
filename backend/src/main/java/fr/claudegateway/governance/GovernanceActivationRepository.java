package fr.claudegateway.governance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accès aux paquets actifs sur les projets (F-51 / SF-51-02).
 *
 * <p><b>Aucune méthode ne lit sans {@code userId}</b>, y compris celles qui portent déjà un
 * {@code workspaceId} : un identifiant de projet reçu d'un appelant ne prouve rien tant qu'on n'a pas
 * dit à qui il appartient.</p>
 */
public interface GovernanceActivationRepository extends JpaRepository<GovernanceActivation, UUID> {

    /** Les paquets actifs sur un projet, du plus ancien au plus récent. */
    List<GovernanceActivation> findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(UUID userId,
            UUID workspaceId);

    /** Une activation précise. */
    Optional<GovernanceActivation> findByUserIdAndWorkspaceIdAndPackageId(UUID userId,
            UUID workspaceId, UUID packageId);

    /** Toutes mes activations d'un paquet donné, tous projets confondus. */
    List<GovernanceActivation> findByUserIdAndPackageId(UUID userId, UUID packageId);

    /** Désactive : retire l'activation. Les fichiers déjà déposés, eux, restent (décision D4). */
    void deleteByUserIdAndWorkspaceIdAndPackageId(UUID userId, UUID workspaceId, UUID packageId);

    /** Nettoyage à la suppression d'un projet. */
    void deleteByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);
}
