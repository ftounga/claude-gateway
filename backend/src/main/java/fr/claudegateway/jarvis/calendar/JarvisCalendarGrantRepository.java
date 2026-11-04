package fr.claudegateway.jarvis.calendar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès à {@link JarvisCalendarGrant} (F-243). Toute résolution filtre sur {@code user_id}. */
@Repository
public interface JarvisCalendarGrantRepository extends JpaRepository<JarvisCalendarGrant, UUID> {

    List<JarvisCalendarGrant> findByUserIdAndRevokedAtIsNullOrderByGrantedAtAsc(UUID userId);

    Optional<JarvisCalendarGrant> findByIdAndUserId(UUID id, UUID userId);

    Optional<JarvisCalendarGrant> findByUserIdAndAccount(UUID userId, String account);

    @Modifying
    @Transactional
    @Query("delete from JarvisCalendarGrant g where g.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
