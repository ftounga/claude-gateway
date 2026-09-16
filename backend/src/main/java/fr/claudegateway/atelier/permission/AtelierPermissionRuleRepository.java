package fr.claudegateway.atelier.permission;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistance des règles de permission d'outil (F-121 / SF-121-02). Toute lecture et toute écriture
 * filtrent sur {@code user_id} <b>et</b> {@code workspace_id} : les règles d'un projet appartiennent à
 * son propriétaire, jamais à un autre.
 */
@Repository
public interface AtelierPermissionRuleRepository extends JpaRepository<AtelierPermissionRule, UUID> {

    /** Toutes les règles d'un projet possédé — lues une fois par tour pour trancher les autorisations. */
    List<AtelierPermissionRule> findByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);

    /** La règle qui porte sur <b>tout un outil</b> (sans préfixe de commande), s'il y en a une. */
    Optional<AtelierPermissionRule> findByUserIdAndWorkspaceIdAndToolAndCommandPrefixIsNull(
            UUID userId, UUID workspaceId, String tool);

    /** La règle qui porte sur un <b>préfixe de commande</b> précis, s'il y en a une. */
    Optional<AtelierPermissionRule> findByUserIdAndWorkspaceIdAndToolAndCommandPrefix(
            UUID userId, UUID workspaceId, String tool, String commandPrefix);

    /** Purge à la suppression du compte (SF-38-14) : les règles ne survivent pas au compte qu'elles décrivent. */
    void deleteByUserId(UUID userId);

    /** Purge à la suppression d'un projet : le filtre porte sur {@code user_id} ET {@code workspace_id}. */
    void deleteByUserIdAndWorkspaceId(UUID userId, UUID workspaceId);
}
