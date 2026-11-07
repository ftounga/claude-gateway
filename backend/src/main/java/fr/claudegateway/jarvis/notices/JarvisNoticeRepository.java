package fr.claudegateway.jarvis.notices;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès aux annonces (F-249). Toute lecture filtre par {@code user_id}. */
@Repository
public interface JarvisNoticeRepository extends JpaRepository<JarvisNotice, UUID> {

    Optional<JarvisNotice> findByUserIdAndDedupKey(UUID userId, String dedupKey);

    Optional<JarvisNotice> findByIdAndUserId(UUID id, UUID userId);

    /** Les annonces ouvertes (non vues, non échues), les plus récentes d'abord. */
    @Query("select n from JarvisNotice n where n.userId = :userId and n.seenAt is null and n.expiresAt > :now "
            + "order by n.updatedAt desc")
    List<JarvisNotice> open(@Param("userId") UUID userId, @Param("now") OffsetDateTime now, Pageable page);

    boolean existsByUserIdAndDedupKey(UUID userId, String dedupKey);

    @Modifying
    @Transactional
    @Query("delete from JarvisNotice n where n.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from JarvisNotice n where n.userId = :userId and n.hostId = :hostId")
    int purgeHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    @Modifying
    @Transactional
    @Query("delete from JarvisNotice n where n.expiresAt < :before")
    int purgeBefore(@Param("before") OffsetDateTime before);
}
