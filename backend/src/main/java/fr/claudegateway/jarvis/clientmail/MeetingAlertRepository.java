package fr.claudegateway.jarvis.clientmail;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès aux alertes données (F-249). */
@Repository
public interface MeetingAlertRepository extends JpaRepository<MeetingAlert, UUID> {

    boolean existsByUserIdAndAlertKey(UUID userId, String alertKey);

    @Modifying
    @Transactional
    @Query("delete from MeetingAlert a where a.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from MeetingAlert a where a.createdAt < :before")
    int purgeBefore(@Param("before") OffsetDateTime before);
}
