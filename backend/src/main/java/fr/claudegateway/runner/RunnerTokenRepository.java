package fr.claudegateway.runner;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistance des jetons runner (F-38 / SF-38-01). Toute lecture propre à un utilisateur filtre sur
 * {@code user_id} (isolation multi-tenant). Aucune logique métier ici.
 */
@Repository
public interface RunnerTokenRepository extends JpaRepository<RunnerToken, UUID> {

    Optional<RunnerToken> findByTokenHash(String tokenHash);

    /** Lecture isolée : un jeton n'est visible que par son propriétaire, sur ce poste. */
    List<RunnerToken> findByUserIdAndHostIdOrderByCreatedAtDesc(UUID userId, UUID hostId);

    /**
     * Dernier battement reçu d'un poste, tous jetons confondus, pour son propriétaire (F-97 /
     * SF-97-01) — {@code null} si aucun battement n'a jamais été reçu.
     */
    @Query("select max(t.lastSeenAt) from RunnerToken t where t.userId = :userId and t.hostId = :hostId")
    OffsetDateTime findLastSeenAt(@Param("userId") UUID userId, @Param("hostId") UUID hostId);

    /**
     * Dernier battement reçu d'un poste, <b>pour le routage</b> seulement (F-97 / SF-97-01) : la cible
     * d'un appel d'outil ne porte que le poste, déjà vérifié possédé par l'appelant. Ne rend qu'un
     * horodatage, qui ne quitte jamais la gateway.
     */
    @Query("select max(t.lastSeenAt) from RunnerToken t where t.hostId = :hostId")
    OffsetDateTime findLastSeenAtForRouting(@Param("hostId") UUID hostId);

    /** Lecture isolée d'un jeton précis (le propriétaire uniquement). */
    Optional<RunnerToken> findByIdAndUserId(UUID id, UUID userId);

    /** Purge à la suppression du compte (SF-38-14) : aucun jeton ne survit à son propriétaire. */
    void deleteByUserId(UUID userId);

    /** Purge à la suppression d'un poste (F-48) : aucun jeton ne survit à sa machine. */
    void deleteByHostId(UUID hostId);
}
