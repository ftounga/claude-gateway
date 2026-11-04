package fr.claudegateway.jarvis.calendar;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès au cache des occurrences (F-243). Toute lecture filtre par {@code user_id}. */
@Repository
public interface JarvisCalendarEventRepository extends JpaRepository<JarvisCalendarEvent, UUID> {

    /** Les occurrences qui touchent [from, to[ (celles de durée nulle comprises), dans l'ordre. */
    @Query("select e from JarvisCalendarEvent e where e.userId = :userId and e.startsAt < :to "
            + "and (e.endsAt > :from or (e.endsAt = e.startsAt and e.startsAt >= :from)) "
            + "order by e.startsAt asc, e.endsAt asc")
    List<JarvisCalendarEvent> overlapping(@Param("userId") UUID userId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    long countByUserIdAndFeedId(UUID userId, UUID feedId);

    long countByUserId(UUID userId);

    @Modifying
    @Transactional
    @Query("delete from JarvisCalendarEvent e where e.userId = :userId and e.feedId = :feedId")
    int purgeFeed(@Param("userId") UUID userId, @Param("feedId") UUID feedId);

    @Modifying
    @Transactional
    @Query("delete from JarvisCalendarEvent e where e.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from JarvisCalendarEvent e where e.endsAt < :before")
    int purgeBefore(@Param("before") OffsetDateTime before);
}
