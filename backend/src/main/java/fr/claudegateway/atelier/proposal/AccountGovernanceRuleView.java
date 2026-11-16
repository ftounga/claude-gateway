package fr.claudegateway.atelier.proposal;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Une règle de tous mes postes, telle que l'écran la montre (F-266 / SF-266-01). */
public record AccountGovernanceRuleView(UUID id, String title, String content, UUID sourceHostId,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    static AccountGovernanceRuleView of(AccountGovernanceRule rule) {
        return new AccountGovernanceRuleView(rule.getId(), rule.getTitle(), rule.getContent(),
                rule.getSourceHostId(), rule.getCreatedAt(), rule.getUpdatedAt());
    }
}
