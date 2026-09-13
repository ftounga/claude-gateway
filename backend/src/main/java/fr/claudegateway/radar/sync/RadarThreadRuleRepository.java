package fr.claudegateway.radar.sync;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Règles de fil (F-100). Toute méthode porte {@code user_id} et {@code host_id}, sauf la purge d'un compte. */
public interface RadarThreadRuleRepository extends JpaRepository<RadarThreadRule, UUID> {

    List<RadarThreadRule> findByUserIdAndHostIdOrderByCreatedAtDesc(UUID userId, UUID hostId);

    List<RadarThreadRule> findByUserIdAndHostIdAndRule(UUID userId, UUID hostId, RadarThreadRule.Rule rule);

    Optional<RadarThreadRule> findByUserIdAndHostIdAndConversationRefAndRule(UUID userId, UUID hostId,
            String conversationRef, RadarThreadRule.Rule rule);

    Optional<RadarThreadRule> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    @Modifying
    @Query("delete from RadarThreadRule r where r.userId = :userId and r.hostId = :hostId")
    int purgeScope(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    @Modifying
    @Query("delete from RadarThreadRule r where r.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
