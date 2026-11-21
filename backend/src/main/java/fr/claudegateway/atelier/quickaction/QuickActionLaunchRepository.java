package fr.claudegateway.atelier.quickaction;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Les derniers lancements des actions rapides (F-275) — toujours sous {@code user_id}. */
public interface QuickActionLaunchRepository extends JpaRepository<QuickActionLaunch, UUID> {

    Optional<QuickActionLaunch> findByUserIdAndActionKey(UUID userId, String actionKey);

    List<QuickActionLaunch> findByUserIdAndActionKeyIn(UUID userId, Collection<String> actionKeys);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from QuickActionLaunch l where l.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
