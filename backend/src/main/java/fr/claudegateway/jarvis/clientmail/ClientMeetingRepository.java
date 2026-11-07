package fr.claudegateway.jarvis.clientmail;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès aux réunions des clients (F-249). Toute lecture d'un compte filtre par {@code user_id}. */
@Repository
public interface ClientMeetingRepository extends JpaRepository<ClientMeeting, UUID> {

    List<ClientMeeting> findByUserIdAndIcalUid(UUID userId, String icalUid);

    boolean existsByUserId(UUID userId);

    @Query("select m from ClientMeeting m where m.userId = :userId and m.startsAt < :to "
            + "and (m.endsAt > :from or (m.endsAt = m.startsAt and m.startsAt >= :from)) "
            + "order by m.startsAt asc, m.endsAt asc")
    List<ClientMeeting> overlapping(@Param("userId") UUID userId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    /** Les réunions (tous comptes) qui commencent dans ]from, to] — travail des alertes, chaque ligne porte son compte. */
    @Query("select m from ClientMeeting m where m.startsAt > :from and m.startsAt <= :to and m.allDay = false")
    List<ClientMeeting> startingBetween(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    @Modifying
    @Transactional
    @Query("delete from ClientMeeting m where m.userId = :userId and m.icalUid = :uid")
    int deleteUid(@Param("userId") UUID userId, @Param("uid") String uid);

    @Modifying
    @Transactional
    @Query("delete from ClientMeeting m where m.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from ClientMeeting m where m.userId = :userId and m.hostId = :hostId")
    int purgeHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    @Modifying
    @Transactional
    @Query("delete from ClientMeeting m where m.endsAt < :before")
    int purgeBefore(@Param("before") OffsetDateTime before);
}
