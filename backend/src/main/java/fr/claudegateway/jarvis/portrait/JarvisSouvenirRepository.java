package fr.claudegateway.jarvis.portrait;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Les souvenirs (F-241 / SF-241-01) — toujours lus sous {@code user_id}. */
public interface JarvisSouvenirRepository extends JpaRepository<JarvisSouvenir, UUID> {

    List<JarvisSouvenir> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

    Optional<JarvisSouvenir> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    @Modifying
    @Transactional
    @Query("delete from JarvisSouvenir s where s.userId = :userId")
    int deleteByUser(@Param("userId") UUID userId);
}
