package fr.claudegateway.jarvis.clientmail;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès au journal des dépôts (F-249). Toute lecture filtre par {@code user_id}. */
@Repository
public interface ClientMailDepositRepository extends JpaRepository<ClientMailDeposit, UUID> {

    boolean existsByUserIdAndHostIdAndContentHash(UUID userId, UUID hostId, String contentHash);

    /** Le dernier dépôt d'un client (quelle que soit son issue). */
    ClientMailDeposit findFirstByUserIdAndHostIdOrderByDepositedAtDesc(UUID userId, UUID hostId);

    /** Les dépôts du compte depuis {@code since} (récapitulatif, rappel). */
    List<ClientMailDeposit> findByUserIdAndDepositedAtGreaterThanEqualOrderByDepositedAtAsc(UUID userId,
            OffsetDateTime since);

    boolean existsByUserIdAndHostIdAndDepositedAtGreaterThanEqual(UUID userId, UUID hostId, OffsetDateTime since);

    long countByUserIdAndHostIdAndOutcome(UUID userId, UUID hostId, String outcome);

    @Modifying
    @Transactional
    @Query("delete from ClientMailDeposit d where d.userId = :userId and d.hostId = :hostId")
    int purgeHost(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    @Modifying
    @Transactional
    @Query("delete from ClientMailDeposit d where d.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("delete from ClientMailDeposit d where d.depositedAt < :before")
    int purgeBefore(@Param("before") OffsetDateTime before);
}
