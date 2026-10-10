package fr.claudegateway.notifications;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Le centre de notifications (F-185 / SF-185-04). Toutes les requêtes portent {@code user_id}. */
@Repository
public interface UserNotificationRepository extends JpaRepository<UserNotification, UUID> {

    List<UserNotification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable page);

    long countByUserIdAndReadAtIsNull(UUID userId);

    @Modifying
    @Query("update UserNotification n set n.readAt = :at where n.userId = :userId and n.id = :id and n.readAt is null")
    int markRead(@Param("userId") UUID userId, @Param("id") UUID id, @Param("at") OffsetDateTime at);

    boolean existsByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("update UserNotification n set n.readAt = :at where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") UUID userId, @Param("at") OffsetDateTime at);

    /** Rétention : bornée à un compte, jamais un balayage global. */
    @Modifying
    @Query("delete from UserNotification n where n.userId = :userId and n.createdAt < :cutoff")
    int deleteOlderThan(@Param("userId") UUID userId, @Param("cutoff") OffsetDateTime cutoff);

    void deleteByUserId(UUID userId);
}
