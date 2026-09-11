package fr.claudegateway.atelier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des workspaces Atelier (F-28). Toute lecture propre à un utilisateur passe par une
 * méthode filtrant sur {@code user_id} (isolation multi-tenant). Aucune logique métier ici.
 */
@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    /** Lecture isolée : un workspace n'est visible que par son propriétaire. */
    Optional<Workspace> findByIdAndUserId(UUID id, UUID userId);

    /** Workspaces d'un utilisateur, les plus récents d'abord (isolation {@code user_id}). */
    List<Workspace> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Projets rattachés à un poste (F-48 / SF-48-01), isolation {@code user_id}. */
    List<Workspace> findByUserIdAndHostId(UUID userId, UUID hostId);

    /**
     * Projets <b>sans poste</b> (F-71 / SF-71-01), isolation {@code user_id} : un dépôt GitHub, une
     * archive importée, ou un projet pas encore rattaché à une machine. Ils existent depuis F-48
     * ({@code host_id} nullable) mais n'apparaissaient sur aucune carte de l'accueil.
     */
    List<Workspace> findByUserIdAndHostIdIsNull(UUID userId);

    /** Purge à la suppression du compte (SF-11-03), après effacement des fichiers du stockage. */
    void deleteByUserId(UUID userId);
}
