package fr.claudegateway.jarvis.portrait;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Le portrait (F-241 / SF-241-01) — clé {@code user_id} : un compte ne lit que sa ligne. */
public interface JarvisPortraitRepository extends JpaRepository<JarvisPortrait, UUID> {

    @Modifying
    @Transactional
    @Query("delete from JarvisPortrait p where p.userId = :userId")
    int deleteByUser(@Param("userId") UUID userId);
}
