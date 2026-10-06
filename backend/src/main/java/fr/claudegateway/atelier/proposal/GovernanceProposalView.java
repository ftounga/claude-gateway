package fr.claudegateway.atelier.proposal;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Ce que l'écran relit d'une proposition (F-177 / SF-177-02) : son statut surtout, qui change après
 * le tour. Jamais le contenu complet du fichier (la carte porte déjà le diff).
 */
public record GovernanceProposalView(UUID id, String status, String type, String scope, String name,
        String path, OffsetDateTime createdAt, OffsetDateTime decidedAt) {

    static GovernanceProposalView of(GovernanceProposal p) {
        return new GovernanceProposalView(p.getId(), p.getStatus(), p.getType(), p.getScope(), p.getName(),
                p.getPath(), p.getCreatedAt(), p.getDecidedAt());
    }
}
