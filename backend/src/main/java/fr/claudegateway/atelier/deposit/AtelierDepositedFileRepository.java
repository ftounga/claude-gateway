package fr.claudegateway.atelier.deposit;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accès aux fichiers déposés (F-115). Toute lecture est filtrée par le couple d'isolation
 * {@code (user_id, workspace_id)} : jamais un {@code findById} nu.
 */
public interface AtelierDepositedFileRepository extends JpaRepository<AtelierDepositedFile, UUID> {

    /**
     * Dépôts <b>non consommés</b> d'un projet possédé, du plus ancien au plus récent — c'est ce que la
     * consigne du prochain tour lira (SF-115-03).
     */
    List<AtelierDepositedFile> findByUserIdAndWorkspaceIdAndConsumedAtIsNullOrderByCreatedAtAsc(
            UUID userId, UUID workspaceId);

    /**
     * Dépôts <b>explicitement désignés</b> et encore libres d'un projet possédé (F-169 / SF-169-02) :
     * exactement les {@code ids} donnés, filtrés par le couple d'isolation {@code (user_id,
     * workspace_id)} <b>et</b> non consommés — c'est ce qu'un envoi avec pièces jointes attache à son
     * message. Un id d'autrui, d'un autre workspace, inconnu ou déjà consommé n'est jamais remonté.
     */
    List<AtelierDepositedFile> findByUserIdAndWorkspaceIdAndIdInAndConsumedAtIsNullOrderByCreatedAtAsc(
            UUID userId, UUID workspaceId, Collection<UUID> ids);

    /**
     * Dépôts joints aux messages donnés d'un projet possédé (F-169 / SF-169-02) : c'est ce que le
     * rechargement du fil ({@code GET /chat}) rend, par message, pour les puces de la bulle. Filtre
     * d'isolation {@code (user_id, workspace_id)} — jamais les pièces jointes d'un autre.
     */
    List<AtelierDepositedFile> findByUserIdAndWorkspaceIdAndMessageIdInOrderByCreatedAtAsc(
            UUID userId, UUID workspaceId, Collection<UUID> messageIds);

    /** Purge des dépôts d'un projet (suppression de projet / de compte). */
    void deleteByWorkspaceId(UUID workspaceId);

    /** Purge des dépôts d'un utilisateur (suppression de compte). */
    void deleteByUserId(UUID userId);
}
