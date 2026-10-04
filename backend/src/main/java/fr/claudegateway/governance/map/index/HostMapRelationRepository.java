package fr.claudegateway.governance.map.index;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Les relations indexées de la carte (F-174 / SF-174-02). Aucune lecture sans {@code user_id} + {@code host_id}. */
@Repository
public interface HostMapRelationRepository extends JpaRepository<HostMapRelation, UUID> {

    List<HostMapRelation> findByUserIdAndHostId(UUID userId, UUID hostId);

    @Modifying
    @Query("DELETE FROM HostMapRelation r WHERE r.sectionId IN :sectionIds")
    int deleteBySectionIds(@Param("sectionIds") Collection<UUID> sectionIds);

    @Modifying
    @Query("DELETE FROM HostMapRelation r WHERE r.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM HostMapRelation r WHERE r.userId = :userId AND r.hostId = :hostId")
    int purgeHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);
}
