package fr.claudegateway.governance.map.index;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Les faits indexés de la carte (F-174 / SF-174-02). Aucune lecture sans {@code user_id} + {@code host_id}. */
@Repository
public interface HostMapFactRepository extends JpaRepository<HostMapFact, UUID> {

    List<HostMapFact> findBySectionIdOrderByLineNoAsc(UUID sectionId);

    List<HostMapFact> findByUserIdAndHostIdOrderByPathAscLineNoAsc(UUID userId, UUID hostId);

    List<HostMapFact> findByUserIdAndHostIdAndIdIn(UUID userId, UUID hostId, Collection<UUID> ids);

    long countByUserIdAndHostId(UUID userId, UUID hostId);

    /** Les faits d'une nature donnée (pièges, échéances) sur la carte de CE poste (SF-174-04). */
    List<HostMapFact> findByUserIdAndHostIdAndKindInOrderByPathAscLineNoAsc(UUID userId, UUID hostId,
            Collection<String> kinds);

    /**
     * Les faits dont la colonne d'identifiants correspond au motif, sur la carte de CE poste. Le
     * motif est bâti par {@link HostMapLikes#exactIdentifier(String)} (égalité exacte, jokers
     * échappés).
     */
    @Query("SELECT f FROM HostMapFact f WHERE f.userId = :userId AND f.hostId = :hostId "
            + "AND f.identifiers LIKE :pattern ESCAPE '\\' ORDER BY f.path, f.lineNo")
    List<HostMapFact> findByIdentifierPattern(@Param("userId") UUID userId,
            @Param("hostId") UUID hostId, @Param("pattern") String pattern);

    /**
     * Les faits dont le texte correspond au motif (insensible à la casse), sur CE poste. Motif bâti
     * par {@link HostMapLikes#contains(String)}.
     */
    @Query("SELECT f FROM HostMapFact f WHERE f.userId = :userId AND f.hostId = :hostId "
            + "AND LOWER(f.text) LIKE :pattern ESCAPE '\\' ORDER BY f.path, f.lineNo")
    List<HostMapFact> findByTextPattern(@Param("userId") UUID userId, @Param("hostId") UUID hostId,
            @Param("pattern") String pattern);

    @Modifying
    @Query("DELETE FROM HostMapFact f WHERE f.sectionId IN :sectionIds")
    int deleteBySectionIds(@Param("sectionIds") Collection<UUID> sectionIds);

    @Modifying
    @Query("DELETE FROM HostMapFact f WHERE f.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM HostMapFact f WHERE f.userId = :userId AND f.hostId = :hostId")
    int purgeHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);
}
