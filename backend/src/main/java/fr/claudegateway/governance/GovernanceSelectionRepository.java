package fr.claudegateway.governance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accès au catalogue personnel (F-51 / SF-51-02).
 *
 * <p><b>Aucune méthode ne lit sans {@code userId}</b> : l'isolation n'est pas une précaution
 * appliquée par les appelants, c'est une propriété de cette interface — il n'existe pas de signature
 * qui permettrait de l'oublier.</p>
 */
public interface GovernanceSelectionRepository extends JpaRepository<GovernanceSelection, UUID> {

    /** Mon catalogue, du plus ancien au plus récent. */
    List<GovernanceSelection> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /** Mes paquets marqués « appliqué par défaut ». */
    List<GovernanceSelection> findByUserIdAndDefaultAppliedTrue(UUID userId);

    /** Une entrée précise de mon catalogue. */
    Optional<GovernanceSelection> findByUserIdAndPackageId(UUID userId, UUID packageId);

    /** Retire une entrée de mon catalogue. */
    void deleteByUserIdAndPackageId(UUID userId, UUID packageId);
}
