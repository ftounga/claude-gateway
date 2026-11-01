package fr.claudegateway.jarvis.preferences;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Les préférences de Jarvis (F-235 / SF-235-09) — clé {@code user_id} : un compte ne lit que sa ligne. */
public interface JarvisPreferenceRepository extends JpaRepository<JarvisPreference, UUID> {

    @Modifying
    @Transactional
    @Query("delete from JarvisPreference p where p.userId = :userId")
    int deleteByUser(@Param("userId") UUID userId);
}
