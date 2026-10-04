package fr.claudegateway.atelier.actions;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Les attentes des terminaux (F-154 / SF-154-01, F-175 / SF-175-01).
 *
 * <p><b>Toute</b> méthode porte {@code user_id} — et celles qui servent un écran portent aussi
 * {@code workspace_id} ou {@code host_id}. Aucune méthode ne lit une attente par son seul
 * identifiant : c'est la règle qui rend le projet d'un autre introuvable.</p>
 */
public interface TerminalActionRepository extends JpaRepository<TerminalAction, UUID> {

    Optional<TerminalAction> findByIdAndUserIdAndWorkspaceId(UUID id, UUID userId, UUID workspaceId);

    /**
     * L'action déjà inscrite pour ce blocage, <b>quel que soit son statut</b> (F-154 / SF-154-02) —
     * y compris annulée : la parole de l'utilisateur prime, on ne recrée pas.
     */
    Optional<TerminalAction> findByUserIdAndWorkspaceIdAndDedupKey(
            UUID userId, UUID workspaceId, String dedupKey);

    /**
     * Une attente du compte par son identifiant (F-175 / SF-175-02) — pour l'agent qui la désigne
     * depuis la liste jointe au tour. L'appelant vérifie ensuite qu'elle est <b>de ce terminal ou de
     * son poste</b> ; le {@code user_id} reste le premier verrou.
     */
    Optional<TerminalAction> findByIdAndUserId(UUID id, UUID userId);

    /** Les attentes du poste portant cette clé, les plus récentes d'abord (F-175 / SF-175-02). */
    List<TerminalAction> findByUserIdAndHostIdAndDedupKeyOrderByCreatedAtDesc(
            UUID userId, UUID hostId, String dedupKey);

    /** Les attentes « à vérifier » du compte (F-175 / SF-175-07), les plus anciennes d'abord. */
    List<TerminalAction> findByUserIdAndReviewPendingTrueOrderByCreatedAtAsc(UUID userId);

    /** Les attentes d'un poste dans ces états (F-175 / SF-175-02 : la liste jointe au tour). */
    List<TerminalAction> findByUserIdAndHostIdAndStatusIn(
            UUID userId, UUID hostId, Collection<TerminalActionStatus> statuses);

    /** Le menu d'un terminal : les plus anciennes d'abord — l'ancienneté est le signal utile. */
    List<TerminalAction> findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(UUID userId, UUID workspaceId);

    List<TerminalAction> findByUserIdAndWorkspaceIdAndStatusInOrderByCreatedAtAsc(
            UUID userId, UUID workspaceId, Collection<TerminalActionStatus> statuses);

    /** Toutes les actions du compte dans ces états : le terminal racine les regroupe. */
    List<TerminalAction> findByUserIdAndStatusInOrderByCreatedAtAsc(
            UUID userId, Collection<TerminalActionStatus> statuses);

    int countByUserIdAndWorkspaceIdAndStatusIn(
            UUID userId, UUID workspaceId, Collection<TerminalActionStatus> statuses);

    /**
     * Le tableau d'un <b>poste</b> (F-175 / SF-175-01) : ses attentes ouvertes, et celles fermées
     * depuis {@code since} — tous terminaux du poste confondus, sous {@code user_id}.
     */
    @Query("select a from TerminalAction a where a.userId = :userId and a.hostId = :hostId "
            + "and (a.status in :open or a.closedAt >= :since) order by a.createdAt asc")
    List<TerminalAction> findBoardOfHost(@Param("userId") UUID userId,
                                         @Param("hostId") UUID hostId,
                                         @Param("open") Collection<TerminalActionStatus> open,
                                         @Param("since") OffsetDateTime since);

    /** Le tableau d'un terminal seul (hébergé, sans poste) : mêmes règles. */
    @Query("select a from TerminalAction a where a.userId = :userId and a.workspaceId = :workspaceId "
            + "and (a.status in :open or a.closedAt >= :since) order by a.createdAt asc")
    List<TerminalAction> findBoardOfWorkspace(@Param("userId") UUID userId,
                                              @Param("workspaceId") UUID workspaceId,
                                              @Param("open") Collection<TerminalActionStatus> open,
                                              @Param("since") OffsetDateTime since);

    /** Purge à la suppression d'un projet : pas de clé étrangère, donc purge explicite. */
    @Modifying
    @Query("delete from TerminalAction a where a.userId = :userId and a.workspaceId = :workspaceId")
    int purgeWorkspace(@Param("userId") UUID userId, @Param("workspaceId") UUID workspaceId);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from TerminalAction a where a.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
