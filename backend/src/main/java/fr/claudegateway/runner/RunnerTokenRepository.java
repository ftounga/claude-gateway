package fr.claudegateway.runner;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des jetons runner (F-38 / SF-38-01). Toute lecture propre à un utilisateur filtre sur
 * {@code user_id} (isolation multi-tenant). Aucune logique métier ici.
 */
@Repository
public interface RunnerTokenRepository extends JpaRepository<RunnerToken, UUID> {

    Optional<RunnerToken> findByTokenHash(String tokenHash);

    /** Lecture isolée : un jeton n'est visible que par son propriétaire, sur ce workspace. */
    List<RunnerToken> findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(UUID userId, UUID workspaceId);

    /** Lecture isolée d'un jeton précis (le propriétaire uniquement). */
    Optional<RunnerToken> findByIdAndUserId(UUID id, UUID userId);

    /** Purge à la suppression du compte (SF-38-14) : aucun jeton ne survit à son propriétaire. */
    void deleteByUserId(UUID userId);
}
