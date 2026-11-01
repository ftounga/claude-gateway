package fr.claudegateway.jarvis.companion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Les annonces à dire (F-232 / SF-232-03). Lues sous {@code user_id} (index user_id, created_at). */
public interface JarvisCompanionAnnouncementRepository extends JpaRepository<JarvisCompanionAnnouncement, UUID> {

    List<JarvisCompanionAnnouncement> findTop10ByUserIdAndCreatedAtGreaterThanOrderByCreatedAtAsc(UUID userId,
            OffsetDateTime after);

    @Modifying
    @Transactional
    @Query("delete from JarvisCompanionAnnouncement a where a.createdAt < :before")
    int deleteOlderThan(@Param("before") OffsetDateTime before);
}
