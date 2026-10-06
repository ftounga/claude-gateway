package fr.claudegateway.atelier.proposal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux propositions de gouvernance — toujours sous {@code user_id} + {@code workspace_id} (F-177). */
public interface GovernanceProposalRepository extends JpaRepository<GovernanceProposal, UUID> {

    Optional<GovernanceProposal> findByIdAndUserIdAndWorkspaceId(UUID id, UUID userId, UUID workspaceId);

    /** Purge à la suppression du compte. */
    @Modifying
    @Query("delete from GovernanceProposal p where p.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
