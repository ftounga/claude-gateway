package fr.claudegateway.atelier;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistance des messages Atelier (F-28 / SF-28-02). Lecture toujours filtrée sur {@code user_id}. */
@Repository
public interface AtelierMessageRepository extends JpaRepository<AtelierMessage, UUID> {

    List<AtelierMessage> findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(UUID workspaceId, UUID userId);

    /**
     * Messages postérieurs à la frontière de rejeu du fil (F-39 / SF-39-04) : ce que l'agent a
     * encore en mémoire après un « nouveau départ ». Filtrée sur {@code user_id} comme toutes les
     * lectures de cette table.
     */
    List<AtelierMessage> findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
            UUID workspaceId, UUID userId, java.time.OffsetDateTime since);

    /** Purge à la suppression du compte (SF-11-03). */
    void deleteByUserId(UUID userId);

    /** Purge des messages d'un workspace supprimé (SF-11-03) : sans elle, ils restent orphelins. */
    void deleteByWorkspaceId(UUID workspaceId);

    /**
     * Combien de <b>tours</b> ont été demandés sur ces projets depuis une date (F-140 / SF-140-01).
     *
     * <p>Un tour = un message de l'utilisateur. C'est l'autre moitié de la mesure : le dénominateur
     * de « combien d'appels d'outils faut-il pour répondre ». Les projets sont ceux du poste, et le
     * filtre porte aussi sur {@code user_id}.</p>
     */
    long countByUserIdAndWorkspaceIdInAndRoleAndCreatedAtGreaterThanEqual(UUID userId,
            java.util.Collection<UUID> workspaceIds, String role, java.time.OffsetDateTime since);
}
