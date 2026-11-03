package fr.claudegateway.jarvis.mail;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès au cache du courrier (F-242). Toute lecture filtre par {@code user_id}. */
@Repository
public interface JarvisMailItemRepository extends JpaRepository<JarvisMailItem, UUID> {

    Optional<JarvisMailItem> findByIdAndUserId(UUID id, UUID userId);

    List<JarvisMailItem> findByUserIdOrderByReceivedAtDesc(UUID userId, Pageable page);

    List<JarvisMailItem> findByUserIdAndTriageOrderByReceivedAtDesc(UUID userId, JarvisMailItem.Triage triage,
            Pageable page);

    List<JarvisMailItem> findByUserIdAndTriageOrderByReceivedAtAsc(UUID userId, JarvisMailItem.Triage triage,
            Pageable page);

    boolean existsByAccountIdAndUidvalidityAndUid(UUID accountId, long uidvalidity, long uid);

    @Query("select i.messageId from JarvisMailItem i where i.accountId = :accountId and i.messageId in :ids")
    List<String> knownMessageIds(@Param("accountId") UUID accountId, @Param("ids") Collection<String> ids);

    /** Les comptes qui ont des courriers en attente de tri (SF-242-03). */
    @Query("select distinct i.userId from JarvisMailItem i where i.triage = :triage")
    List<UUID> usersWithTriage(@Param("triage") JarvisMailItem.Triage triage, Pageable page);

    long countByUserId(UUID userId);

    @Modifying
    @Transactional
    @Query("delete from JarvisMailItem i where i.userId = :userId and i.accountId = :accountId")
    int purgeAccount(@Param("userId") UUID userId, @Param("accountId") UUID accountId);

    @Modifying
    @Transactional
    @Query("delete from JarvisMailItem i where i.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from JarvisMailItem i where i.receivedAt < :before")
    int purgeBefore(@Param("before") OffsetDateTime before);
}
