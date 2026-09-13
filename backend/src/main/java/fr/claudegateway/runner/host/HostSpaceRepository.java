package fr.claudegateway.runner.host;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistance des espaces d'un poste (F-106 / SF-106-01). Toute lecture filtre {@code user_id} ;
 * seule la purge d'un poste déjà vérifié se fait par {@code host_id}. Aucune logique métier ici.
 */
@Repository
public interface HostSpaceRepository extends JpaRepository<HostSpace, UUID> {

    List<HostSpace> findByUserId(UUID userId);

    List<HostSpace> findByUserIdAndHostId(UUID userId, UUID hostId);

    @Modifying
    @Query("delete from HostSpace s where s.userId = :userId and s.hostId = :hostId and s.space = :space")
    int deleteOne(@Param("userId") UUID userId, @Param("hostId") UUID hostId,
            @Param("space") ClientSpace space);

    @Modifying
    @Query("delete from HostSpace s where s.userId = :userId and s.hostId = :hostId")
    int deleteByUserIdAndHostId(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    @Modifying
    @Query("delete from HostSpace s where s.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
