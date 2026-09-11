package fr.claudegateway.terminals;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistance des places de terminal vivant (F-70 / SF-70-01).
 *
 * <p><b>Toute</b> lecture filtre sur {@code user_id} : il n'existe volontairement aucune méthode qui
 * lise une place par son seul identifiant. Un registre dont on peut extraire une ligne sans dire
 * pour qui finit toujours par être appelé sans le dire.</p>
 */
@Repository
public interface LiveTerminalRepository extends JpaRepository<LiveTerminal, UUID> {

    /**
     * Places <b>vivantes</b> d'un utilisateur, les plus anciennes d'abord. L'ordre n'est pas
     * cosmétique : c'est lui qui décide, en cas de course, qui garde sa place — le premier arrivé.
     */
    List<LiveTerminal> findByUserIdAndLastSeenAtAfterOrderByOpenedAtAsc(UUID userId,
            OffsetDateTime cutoff);

    /** La place d'un onglet donné, vivante ou non (c'est l'appelant qui la renouvelle). */
    Optional<LiveTerminal> findByUserIdAndSessionId(UUID userId, String sessionId);

    /** Libération explicite : un onglet ne peut libérer que sa propre place. */
    void deleteByUserIdAndSessionId(UUID userId, String sessionId);

    /** Purge des places expirées d'un utilisateur — bornée à lui, jamais un balayage global. */
    @Modifying
    @Query("delete from LiveTerminal t where t.userId = :userId and t.lastSeenAt <= :cutoff")
    int deleteStale(@Param("userId") UUID userId, @Param("cutoff") OffsetDateTime cutoff);

    /** Purge à la suppression du compte : aucune place ne survit à son propriétaire. */
    void deleteByUserId(UUID userId);
}
