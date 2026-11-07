package fr.claudegateway.jarvis.clientmail;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès aux rattachements (F-249). Toute lecture filtre par {@code user_id}. */
@Repository
public interface ClientMailSubjectLinkRepository extends JpaRepository<ClientMailSubjectLink, UUID> {

    List<ClientMailSubjectLink> findByUserIdAndItemIdIn(UUID userId, Collection<UUID> itemIds);

    List<ClientMailSubjectLink> findByUserIdAndCreatedAtGreaterThanEqual(UUID userId, OffsetDateTime since);

    @Modifying
    @Transactional
    @Query("delete from ClientMailSubjectLink l where l.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from ClientMailSubjectLink l where l.userId = :userId and l.hostId = :hostId")
    int purgeHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    @Modifying
    @Transactional
    @Query("delete from ClientMailSubjectLink l where l.createdAt < :before")
    int purgeBefore(@Param("before") OffsetDateTime before);
}
