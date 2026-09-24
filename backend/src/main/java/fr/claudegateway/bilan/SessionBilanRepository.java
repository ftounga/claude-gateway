package fr.claudegateway.bilan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Les bilans gardés (F-155 / SF-155-04).
 *
 * <p><b>Toute</b> méthode porte {@code user_id} : un bilan n'est jamais lu par son seul
 * identifiant.</p>
 */
public interface SessionBilanRepository extends JpaRepository<SessionBilan, UUID> {

    Optional<SessionBilan> findByIdAndUserId(UUID id, UUID userId);

    List<SessionBilan> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** Purge à la suppression du compte — pas de clé étrangère, donc purge nommée. */
    @Modifying
    @Query("delete from SessionBilan b where b.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
