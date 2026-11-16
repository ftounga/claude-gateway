package fr.claudegateway.atelier.proposal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Les règles de tous mes postes (F-266) — toujours sous {@code user_id}. */
public interface AccountGovernanceRuleRepository extends JpaRepository<AccountGovernanceRule, UUID> {

    List<AccountGovernanceRule> findByUserIdOrderByTitleKeyAsc(UUID userId);

    Optional<AccountGovernanceRule> findByUserIdAndTitleKey(UUID userId, String titleKey);

    Optional<AccountGovernanceRule> findByIdAndUserId(UUID id, UUID userId);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from AccountGovernanceRule r where r.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
