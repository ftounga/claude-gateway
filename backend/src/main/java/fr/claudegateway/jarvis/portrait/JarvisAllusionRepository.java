package fr.claudegateway.jarvis.portrait;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Le journal des allusions (F-241 / SF-241-04) — toujours lu sous {@code user_id}. */
public interface JarvisAllusionRepository extends JpaRepository<JarvisAllusion, UUID> {

    List<JarvisAllusion> findTop30ByUserIdAndAtGreaterThanEqualOrderByAtDesc(UUID userId, OffsetDateTime since);

    @Modifying
    @Transactional
    @Query("delete from JarvisAllusion a where a.userId = :userId")
    int deleteByUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from JarvisAllusion a where a.at < :limit")
    int deleteOlderThan(@Param("limit") OffsetDateTime limit);
}
