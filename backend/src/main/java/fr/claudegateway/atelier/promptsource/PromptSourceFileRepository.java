package fr.claudegateway.atelier.promptsource;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance du cache des sources de la consigne (F-148 / SF-148-06).
 *
 * <p><b>Aucune lecture sans le couple utilisateur + projet.</b> C'est la garantie d'isolation de
 * cette table : la copie d'un projet ne peut pas être servie au tour d'un autre, y compris entre deux
 * projets du même utilisateur ou du même poste.</p>
 */
@Repository
public interface PromptSourceFileRepository extends JpaRepository<PromptSourceFile, UUID> {

    Optional<PromptSourceFile> findByUserIdAndWorkspaceIdAndPath(UUID userId, UUID workspaceId,
            String path);

    /** Le cache d'un projet est-il amorcé (au moins une ligne rangée) ? */
    boolean existsByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    /** Purge à la suppression du compte : la copie ne survit pas à son propriétaire. */
    void deleteByUserId(UUID userId);

    /** Purge à la suppression d'un projet : la copie ne survit pas au workspace. */
    void deleteByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);
}
