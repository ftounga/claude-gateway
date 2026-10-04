package fr.claudegateway.governance.map.index;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Les sections indexées de la carte (F-174 / SF-174-02). Aucune lecture métier sans {@code user_id}. */
@Repository
public interface HostMapSectionRepository extends JpaRepository<HostMapSection, UUID> {

    List<HostMapSection> findByUserIdAndHostIdAndPathOrderByOrdinalAsc(UUID userId, UUID hostId,
            String path);

    List<HostMapSection> findByUserIdAndHostIdOrderByPathAscOrdinalAsc(UUID userId, UUID hostId);

    /**
     * Sections à extraire, toutes cartes confondues, les plus anciennes d'abord. Balayage du
     * travailleur : chaque extraction n'écrit que sur SA section, sous son propre couple
     * {@code (user_id, host_id)} — aucun tenant n'est croisé.
     */
    List<HostMapSection> findByStatusOrderByCreatedAtAsc(String status, Pageable page);

    long countByUserIdAndHostIdAndStatus(UUID userId, UUID hostId, String status);

    @Modifying
    @Query("DELETE FROM HostMapSection s WHERE s.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM HostMapSection s WHERE s.userId = :userId AND s.hostId = :hostId")
    int purgeHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);
}
