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

/** Accès aux propositions « ignorer » (F-249). Toute lecture filtre par {@code user_id}. */
@Repository
public interface ClientMailIgnoreOfferRepository extends JpaRepository<ClientMailIgnoreOffer, UUID> {

    Optional<ClientMailIgnoreOffer> findByIdAndUserId(UUID id, UUID userId);

    List<ClientMailIgnoreOffer> findByUserIdAndHostId(UUID userId, UUID hostId);

    List<ClientMailIgnoreOffer> findByUserIdAndStatusOrderByOccurrencesDesc(UUID userId, String status);

    long countByUserIdAndHostIdAndStatus(UUID userId, UUID hostId, String status);

    @Modifying
    @Transactional
    @Query("delete from ClientMailIgnoreOffer o where o.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from ClientMailIgnoreOffer o where o.userId = :userId and o.hostId = :hostId")
    int purgeHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);
}
