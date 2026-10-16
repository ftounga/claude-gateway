package fr.claudegateway.atelier.besoin;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux besoins — toujours sous {@code user_id} (F-205). Jamais un {@code findById} nu. */
public interface AppRequestRepository extends JpaRepository<AppRequest, UUID> {

    Optional<AppRequest> findByIdAndUserIdAndWorkspaceId(UUID id, UUID userId, UUID workspaceId);

    Optional<AppRequest> findByIdAndUserId(UUID id, UUID userId);

    List<AppRequest> findByUserIdAndStatusInOrderByCreatedAtDesc(UUID userId, Collection<String> statuses);

    /** Identifiants des besoins d'un compte : les préfixes de stockage à effacer avec lui. */
    @Query("select r.id from AppRequest r where r.userId = :userId")
    List<UUID> findIdsByUserId(@Param("userId") UUID userId);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from AppRequest r where r.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
