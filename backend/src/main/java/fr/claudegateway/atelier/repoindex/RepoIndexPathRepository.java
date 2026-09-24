package fr.claudegateway.atelier.repoindex;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance de l'index de repo persistant (F-148 / SF-148-07).
 *
 * <p><b>Aucune lecture sans le couple utilisateur + projet.</b> C'est la garantie d'isolation de
 * cette table : l'index d'un projet ne peut pas être servi au tour d'un autre.</p>
 */
@Repository
public interface RepoIndexPathRepository extends JpaRepository<RepoIndexEntry, UUID> {

    Optional<RepoIndexEntry> findByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    boolean existsByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    /** Purge à la suppression du compte : l'index ne survit pas à son propriétaire. */
    void deleteByUserId(UUID userId);

    /**
     * Combien de lignes ce compte a-t-il ici (F-156 / SF-156-03) : <b>zéro</b> prouve que l'index du dépôt
     * n'a jamais été alimentée — une capacité <b>dormante</b>, qui ne demande aucun développement
     * mais qu'on s'en aperçoive.
     */
    long countByUserId(UUID userId);

    /** Purge à la suppression d'un projet : l'index ne survit pas au workspace. */
    void deleteByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);
}
