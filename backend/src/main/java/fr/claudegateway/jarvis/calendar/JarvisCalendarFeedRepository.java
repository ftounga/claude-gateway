package fr.claudegateway.jarvis.calendar;

import java.time.OffsetDateTime;
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

/**
 * Accès à {@link JarvisCalendarFeed} (F-243). Toute résolution par id passe par {@link #findByIdAndUserId} : un agenda
 * d'autrui est introuvable (404), jamais « interdit ».
 */
@Repository
public interface JarvisCalendarFeedRepository extends JpaRepository<JarvisCalendarFeed, UUID> {

    List<JarvisCalendarFeed> findByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<JarvisCalendarFeed> findByIdAndUserId(UUID id, UUID userId);

    Optional<JarvisCalendarFeed> findByUserIdAndUrlHash(UUID userId, String urlHash);

    long countByUserId(UUID userId);

    @Modifying
    @Transactional
    @Query("delete from JarvisCalendarFeed f where f.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    /** Les agendas dus : échéance passée, bail libre. */
    @Query("select f.id from JarvisCalendarFeed f where (f.nextPollAt is null or f.nextPollAt <= :now) "
            + "and (f.leaseUntil is null or f.leaseUntil < :now) order by f.nextPollAt asc")
    List<UUID> dueIds(@Param("now") OffsetDateTime now, Pageable page);

    /** Réservation par {@code UPDATE} conditionnel (patron {@code claimSlot}) : un seul pod gagne. */
    @Modifying
    @Transactional
    @Query("update JarvisCalendarFeed f set f.leaseUntil = :until where f.id = :id "
            + "and (f.leaseUntil is null or f.leaseUntil < :now)")
    int claim(@Param("id") UUID id, @Param("now") OffsetDateTime now, @Param("until") OffsetDateTime until);
}
