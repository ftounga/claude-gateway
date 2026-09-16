package fr.claudegateway.atelier.deposit;

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

    /** Purge des dépôts d'un projet (suppression de projet / de compte). */
    void deleteByWorkspaceId(UUID workspaceId);

    /** Purge des dépôts d'un utilisateur (suppression de compte). */
    void deleteByUserId(UUID userId);
}
