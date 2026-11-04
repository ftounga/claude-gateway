package fr.claudegateway.jarvis.calendar;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès aux actions d'agenda (F-243 / SF-243-03), patron {@code JarvisMailActionRepository}. Filtre {@code user_id}. */
@Repository
public interface JarvisCalendarActionRepository extends JpaRepository<JarvisCalendarAction, UUID> {

    Optional<JarvisCalendarAction> findFirstByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtDesc(UUID userId,
            UUID workspaceId, JarvisCalendarAction.Status status);

    /** Le « oui » : PENDING → CONFIRMED, une seule fois. */
    @Modifying
    @Transactional
    @Query("update JarvisCalendarAction a set a.status = "
            + "fr.claudegateway.jarvis.calendar.JarvisCalendarAction.Status.CONFIRMED, a.confirmedByMessageId = :messageId "
            + "where a.id = :id and a.userId = :userId "
            + "and a.status = fr.claudegateway.jarvis.calendar.JarvisCalendarAction.Status.PENDING and a.expiresAt > :now")
    int confirm(@Param("id") UUID id, @Param("userId") UUID userId, @Param("messageId") UUID messageId,
            @Param("now") OffsetDateTime now);

    /** Clôt une action encore en attente (annulée, expirée, remplacée). */
    @Modifying
    @Transactional
    @Query("update JarvisCalendarAction a set a.status = :status where a.id = :id and a.userId = :userId "
            + "and a.status = fr.claudegateway.jarvis.calendar.JarvisCalendarAction.Status.PENDING")
    int close(@Param("id") UUID id, @Param("userId") UUID userId,
            @Param("status") JarvisCalendarAction.Status status);

    @Modifying
    @Transactional
    @Query("delete from JarvisCalendarAction a where a.userId = :userId and a.grantId = :grantId")
    int purgeGrant(@Param("userId") UUID userId, @Param("grantId") UUID grantId);

    @Modifying
    @Transactional
    @Query("delete from JarvisCalendarAction a where a.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
