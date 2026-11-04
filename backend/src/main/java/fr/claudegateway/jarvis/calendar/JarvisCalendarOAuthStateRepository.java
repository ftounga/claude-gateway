package fr.claudegateway.jarvis.calendar;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès aux parcours OAuth en cours (F-243 / SF-243-03). */
@Repository
public interface JarvisCalendarOAuthStateRepository extends JpaRepository<JarvisCalendarOAuthState, String> {

    /** Consommation à usage unique : un seul appel gagne (rend 1). */
    @Modifying
    @Transactional
    @Query("delete from JarvisCalendarOAuthState s where s.stateHash = :hash")
    int consume(@Param("hash") String hash);

    @Modifying
    @Transactional
    @Query("delete from JarvisCalendarOAuthState s where s.expiresAt < :now")
    int purgeExpired(@Param("now") OffsetDateTime now);

    @Modifying
    @Transactional
    @Query("delete from JarvisCalendarOAuthState s where s.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
