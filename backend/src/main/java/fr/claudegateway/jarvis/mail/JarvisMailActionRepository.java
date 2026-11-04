package fr.claudegateway.jarvis.mail;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès aux actions de courrier (F-242 / SF-242-06). Toute lecture filtre par {@code user_id}. */
@Repository
public interface JarvisMailActionRepository extends JpaRepository<JarvisMailAction, UUID> {

    Optional<JarvisMailAction> findFirstByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtDesc(UUID userId,
            UUID workspaceId, JarvisMailAction.Status status);

    List<JarvisMailAction> findByUserIdAndStatus(UUID userId, JarvisMailAction.Status status);

    /** Le « oui » : PENDING → CONFIRMED, une seule fois (idempotence : un « oui » répété ne trouve plus rien). */
    @Modifying
    @Transactional
    @Query("update JarvisMailAction a set a.status = fr.claudegateway.jarvis.mail.JarvisMailAction.Status.CONFIRMED, "
            + "a.confirmedByMessageId = :messageId where a.id = :id and a.userId = :userId "
            + "and a.status = fr.claudegateway.jarvis.mail.JarvisMailAction.Status.PENDING and a.expiresAt > :now")
    int confirm(@Param("id") UUID id, @Param("userId") UUID userId, @Param("messageId") UUID messageId,
            @Param("now") OffsetDateTime now);

    /** Clôt une action encore en attente (annulée, expirée, remplacée). */
    @Modifying
    @Transactional
    @Query("update JarvisMailAction a set a.status = :status where a.id = :id and a.userId = :userId "
            + "and a.status = fr.claudegateway.jarvis.mail.JarvisMailAction.Status.PENDING")
    int close(@Param("id") UUID id, @Param("userId") UUID userId, @Param("status") JarvisMailAction.Status status);

    @Modifying
    @Transactional
    @Query("delete from JarvisMailAction a where a.userId = :userId and a.accountId = :accountId")
    int purgeAccount(@Param("userId") UUID userId, @Param("accountId") UUID accountId);

    @Modifying
    @Transactional
    @Query("delete from JarvisMailAction a where a.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
