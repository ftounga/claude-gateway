package fr.claudegateway.governance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accès aux paquets actifs sur les <b>postes</b> (F-51 / SF-51-02, regrainé par F-75 / SF-75-01).
 *
 * <p><b>Aucune méthode ne lit sans {@code userId}</b>, y compris celles qui portent déjà un
 * {@code hostId} : un identifiant de poste reçu d'un appelant ne prouve rien tant qu'on n'a pas dit
 * à qui il appartient.</p>
 */
public interface GovernanceActivationRepository extends JpaRepository<GovernanceActivation, UUID> {

    /** Les paquets actifs sur un poste, du plus ancien au plus récent. */
    List<GovernanceActivation> findByUserIdAndHostIdOrderByCreatedAtAsc(UUID userId, UUID hostId);

    /** Une activation précise. */
    Optional<GovernanceActivation> findByUserIdAndHostIdAndPackageId(UUID userId, UUID hostId,
            UUID packageId);

    /** Toutes mes activations d'un paquet donné, tous postes confondus. */
    List<GovernanceActivation> findByUserIdAndPackageId(UUID userId, UUID packageId);

    /** Désactive : retire l'activation. Les fichiers déjà déposés, eux, restent (décision D4). */
    void deleteByUserIdAndHostIdAndPackageId(UUID userId, UUID hostId, UUID packageId);

    /** Nettoyage à la suppression d'un poste : la gouvernance ne survit pas à la machine. */
    void deleteByUserIdAndHostId(UUID userId, UUID hostId);
}
