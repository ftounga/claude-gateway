package fr.claudegateway.jarvis.audition;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Les choix de voix de Jarvis (F-233 / SF-233-07) — toujours sous {@code user_id}. */
public interface JarvisVoiceChoiceRepository extends JpaRepository<JarvisVoiceChoice, UUID> {

    Optional<JarvisVoiceChoice> findFirstByUserIdOrderByChosenAtDesc(UUID userId);

    long countByUserId(UUID userId);

    @Modifying
    @Transactional
    @Query("delete from JarvisVoiceChoice c where c.userId = :userId")
    int deleteByUser(@Param("userId") UUID userId);
}
