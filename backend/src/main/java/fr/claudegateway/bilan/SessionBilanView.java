package fr.claudegateway.bilan;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Un bilan tel que la <b>liste</b> le lit (F-155 / SF-155-04) : les cinq chiffres qui permettent de
 * comparer deux semaines d'un coup d'œil, sans désérialiser les photographies.
 */
public record SessionBilanView(
        UUID id,
        UUID workspaceId,
        String workspaceName,
        OffsetDateTime fromAt,
        OffsetDateTime toAt,
        String origin,
        int turns,
        BigDecimal costEur,
        int cacheShare,
        int suggestionCount,
        int discardedCount,
        OffsetDateTime createdAt) {

    public static SessionBilanView from(SessionBilan bilan) {
        return new SessionBilanView(bilan.getId(), bilan.getWorkspaceId(), bilan.getWorkspaceName(),
                bilan.getFromAt(), bilan.getToAt(), bilan.getOrigin(), bilan.getTurns(),
                bilan.getCostEur(), bilan.getCacheShare(), bilan.getSuggestionCount(),
                bilan.getDiscardedCount(), bilan.getCreatedAt());
    }
}
