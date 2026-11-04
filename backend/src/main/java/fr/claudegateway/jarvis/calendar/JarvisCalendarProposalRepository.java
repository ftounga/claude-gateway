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

/** Accès aux rendez-vous repérés (F-243 / SF-243-04). Toute lecture filtre par {@code user_id}. */
@Repository
public interface JarvisCalendarProposalRepository extends JpaRepository<JarvisCalendarProposal, UUID> {

    List<JarvisCalendarProposal> findByUserIdAndMailItemIdOrderByCreatedAtDesc(UUID userId, UUID mailItemId);

    Optional<JarvisCalendarProposal> findFirstByUserIdAndMailItemIdAndKind(UUID userId, UUID mailItemId,
            JarvisCalendarProposal.Kind kind);

    /** Les propositions à dire, non expirées, la plus proche d'abord (les demandes sans créneau en dernier). */
    @Query("select p from JarvisCalendarProposal p where p.userId = :userId "
            + "and p.status = fr.claudegateway.jarvis.calendar.JarvisCalendarProposal.Status.OPEN "
            + "and p.briefedAt is null and p.expiresAt > :now order by p.startsAt asc nulls last, p.createdAt asc")
    List<JarvisCalendarProposal> toSay(@Param("userId") UUID userId, @Param("now") OffsetDateTime now, Pageable page);

    /** Les propositions avec créneau qui touchent [from, to[ (vue d'agenda, en pointillé). */
    @Query("select p from JarvisCalendarProposal p where p.userId = :userId and p.startsAt < :to and p.endsAt > :from "
            + "and p.inCalendar = false and p.expiresAt > :now "
            + "and p.kind in (fr.claudegateway.jarvis.calendar.JarvisCalendarProposal.Kind.INVITATION, "
            + "fr.claudegateway.jarvis.calendar.JarvisCalendarProposal.Kind.PROPOSAL) "
            + "and p.status in (fr.claudegateway.jarvis.calendar.JarvisCalendarProposal.Status.OPEN, "
            + "fr.claudegateway.jarvis.calendar.JarvisCalendarProposal.Status.SAID) order by p.startsAt asc")
    List<JarvisCalendarProposal> pendingBetween(@Param("userId") UUID userId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, @Param("now") OffsetDateTime now);

    @Modifying
    @Transactional
    @Query("delete from JarvisCalendarProposal p where p.userId = :userId and p.accountId = :accountId")
    int purgeAccount(@Param("userId") UUID userId, @Param("accountId") UUID accountId);

    @Modifying
    @Transactional
    @Query("delete from JarvisCalendarProposal p where p.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from JarvisCalendarProposal p where p.expiresAt < :before")
    int purgeBefore(@Param("before") OffsetDateTime before);
}
