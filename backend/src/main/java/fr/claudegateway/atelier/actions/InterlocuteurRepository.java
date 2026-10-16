package fr.claudegateway.atelier.actions;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Les interlocuteurs (F-180 / SF-180-03). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface InterlocuteurRepository extends JpaRepository<Interlocuteur, UUID> {

    Optional<Interlocuteur> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    List<Interlocuteur> findByUserIdAndHostIdOrderByDisplayNameAsc(UUID userId, UUID hostId);

    Optional<Interlocuteur> findByUserIdAndHostIdAndNormalizedName(UUID userId, UUID hostId, String normalizedName);

    List<Interlocuteur> findByUserIdAndHostIdAndIdIn(UUID userId, UUID hostId, java.util.Collection<UUID> ids);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from Interlocuteur i where i.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
