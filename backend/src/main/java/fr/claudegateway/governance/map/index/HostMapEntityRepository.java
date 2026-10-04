package fr.claudegateway.governance.map.index;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Les entités indexées de la carte (F-174 / SF-174-02). Aucune lecture sans {@code user_id} + {@code host_id}. */
@Repository
public interface HostMapEntityRepository extends JpaRepository<HostMapEntity, UUID> {

    List<HostMapEntity> findBySectionId(UUID sectionId);

    List<HostMapEntity> findByUserIdAndHostIdOrderByKindAscLabelNormAsc(UUID userId, UUID hostId);

    @Modifying
    @Query("DELETE FROM HostMapEntity e WHERE e.sectionId IN :sectionIds")
    int deleteBySectionIds(@Param("sectionIds") Collection<UUID> sectionIds);

    @Modifying
    @Query("DELETE FROM HostMapEntity e WHERE e.sectionId = :sectionId AND e.origin = :origin")
    int deleteBySectionIdAndOrigin(@Param("sectionId") UUID sectionId, @Param("origin") String origin);

    @Modifying
    @Query("DELETE FROM HostMapEntity e WHERE e.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM HostMapEntity e WHERE e.userId = :userId AND e.hostId = :hostId")
    int purgeHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);
}
