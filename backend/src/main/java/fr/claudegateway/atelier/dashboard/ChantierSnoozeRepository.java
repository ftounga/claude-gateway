package fr.claudegateway.atelier.dashboard;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Les chantiers reportés (F-220 / SF-220-05) — toujours sous {@code user_id}. */
public interface ChantierSnoozeRepository extends JpaRepository<ChantierSnooze, UUID> {

    Optional<ChantierSnooze> findByUserIdAndWorkspaceIdAndChantierNumber(UUID userId, UUID workspaceId,
            int chantierNumber);

    /** Les reports encore en cours des sujets donnés d'un utilisateur (le tableau du client). */
    List<ChantierSnooze> findByUserIdAndWorkspaceIdInAndSnoozedUntilAfter(UUID userId,
            Collection<UUID> workspaceIds, OffsetDateTime now);

    /** Les reports encore en cours d'un utilisateur (le rappel des dormants). */
    List<ChantierSnooze> findByUserIdAndSnoozedUntilAfter(UUID userId, OffsetDateTime now);

    @Modifying
    @Query("delete from ChantierSnooze s where s.userId = :userId and s.workspaceId = :workspaceId "
            + "and s.chantierNumber = :number")
    int deleteChantier(@Param("userId") UUID userId, @Param("workspaceId") UUID workspaceId,
            @Param("number") int number);
}
