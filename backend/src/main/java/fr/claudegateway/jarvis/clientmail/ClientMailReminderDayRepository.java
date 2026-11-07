package fr.claudegateway.jarvis.clientmail;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès aux rappels du jour (F-249). */
@Repository
public interface ClientMailReminderDayRepository extends JpaRepository<ClientMailReminderDay, UUID> {

    Optional<ClientMailReminderDay> findByUserIdAndHostIdAndReminderDay(UUID userId, UUID hostId, LocalDate day);

    /** Avance le compte d'un rappel : un seul preneur (deux pods n'envoient jamais le même rappel). */
    @Modifying
    @Transactional
    @Query("update ClientMailReminderDay r set r.sentCount = :next, r.lastSentAt = :now where r.id = :id "
            + "and r.sentCount = :current and r.depositedAt is null")
    int advance(@Param("id") UUID id, @Param("current") int current, @Param("next") int next,
            @Param("now") OffsetDateTime now);

    @Modifying
    @Transactional
    @Query("delete from ClientMailReminderDay r where r.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from ClientMailReminderDay r where r.userId = :userId and r.hostId = :hostId")
    int purgeHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    @Modifying
    @Transactional
    @Query("delete from ClientMailReminderDay r where r.reminderDay < :before")
    int purgeBefore(@Param("before") LocalDate before);
}
