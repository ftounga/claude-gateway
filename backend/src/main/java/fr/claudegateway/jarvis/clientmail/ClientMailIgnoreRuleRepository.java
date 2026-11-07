package fr.claudegateway.jarvis.clientmail;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès aux règles « ignorer » (F-249). Toute lecture filtre par {@code user_id}. */
@Repository
public interface ClientMailIgnoreRuleRepository extends JpaRepository<ClientMailIgnoreRule, UUID> {

    Optional<ClientMailIgnoreRule> findByIdAndUserId(UUID id, UUID userId);

    List<ClientMailIgnoreRule> findByUserIdAndHostId(UUID userId, UUID hostId);

    List<ClientMailIgnoreRule> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Modifying
    @Transactional
    @Query("delete from ClientMailIgnoreRule r where r.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from ClientMailIgnoreRule r where r.userId = :userId and r.hostId = :hostId")
    int purgeHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);
}
