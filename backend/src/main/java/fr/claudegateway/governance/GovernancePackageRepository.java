package fr.claudegateway.governance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Accès aux paquets de gouvernance (F-51 / SF-51-01). */
public interface GovernancePackageRepository extends JpaRepository<GovernancePackage, UUID> {

    /** Le catalogue tel que le voit un utilisateur : les paquets publiés, par nom. */
    List<GovernancePackage> findByPublishedTrueOrderByNameAsc();

    /** Le catalogue tel que le voit l'admin : tout, publié ou non. */
    List<GovernancePackage> findAllByOrderByNameAsc();

    /** Sert l'unicité du slug — vérifiée avant l'insertion pour rendre un 409 lisible. */
    Optional<GovernancePackage> findBySlug(String slug);

    /** Les paquets d'une liste d'identifiants, pour résoudre une sélection ou des activations. */
    List<GovernancePackage> findByIdIn(List<UUID> ids);
}
