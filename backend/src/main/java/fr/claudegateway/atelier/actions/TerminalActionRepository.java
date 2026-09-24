package fr.claudegateway.atelier.actions;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Les actions d'un terminal (F-154 / SF-154-01).
 *
 * <p><b>Toute</b> méthode porte {@code user_id} — et celles qui servent un écran portent aussi
 * {@code workspace_id}. Aucune méthode ne lit une action par son seul identifiant : c'est la règle
 * qui rend le projet d'un autre introuvable.</p>
 */
public interface TerminalActionRepository extends JpaRepository<TerminalAction, UUID> {

    Optional<TerminalAction> findByIdAndUserIdAndWorkspaceId(UUID id, UUID userId, UUID workspaceId);

    /**
     * L'action déjà inscrite pour ce blocage, <b>quel que soit son statut</b> (F-154 / SF-154-02) —
     * y compris annulée : la parole de l'utilisateur prime, on ne recrée pas.
     */
    Optional<TerminalAction> findByUserIdAndWorkspaceIdAndDedupKey(
            UUID userId, UUID workspaceId, String dedupKey);

    /** Le menu d'un terminal : les plus anciennes d'abord — l'ancienneté est le signal utile. */
    List<TerminalAction> findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(UUID userId, UUID workspaceId);

    List<TerminalAction> findByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtAsc(
            UUID userId, UUID workspaceId, TerminalActionStatus status);

    /** Toutes les actions du compte dans un état donné : le terminal racine les regroupe. */
    List<TerminalAction> findByUserIdAndStatusOrderByCreatedAtAsc(UUID userId, TerminalActionStatus status);

    int countByUserIdAndWorkspaceIdAndStatus(UUID userId, UUID workspaceId, TerminalActionStatus status);

    /** Purge à la suppression d'un projet : pas de clé étrangère, donc purge explicite. */
    @Modifying
    @Query("delete from TerminalAction a where a.userId = :userId and a.workspaceId = :workspaceId")
    int purgeWorkspace(@Param("userId") UUID userId, @Param("workspaceId") UUID workspaceId);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from TerminalAction a where a.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
