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

/** Accès aux clients actifs (F-249). Toute lecture d'un compte filtre par {@code user_id}. */
@Repository
public interface ClientMailHostRepository extends JpaRepository<ClientMailHost, UUID> {

    Optional<ClientMailHost> findByUserIdAndHostId(UUID userId, UUID hostId);

    List<ClientMailHost> findByUserId(UUID userId);

    /** Tous les clients actifs (travail du rappel ; chaque ligne porte son compte). */
    List<ClientMailHost> findByReminderEnabledTrue();

    @Modifying
    @Transactional
    @Query("delete from ClientMailHost h where h.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from ClientMailHost h where h.userId = :userId and h.hostId = :hostId")
    int purgeHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);
}
