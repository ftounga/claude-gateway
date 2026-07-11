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
}
