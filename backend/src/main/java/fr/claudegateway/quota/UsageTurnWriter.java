package fr.claudegateway.quota;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Écriture d'une ligne du journal de consommation (F-61 / SF-61-01), dans une transaction
 * <b>propre</b>.
 *
 * <p>Pourquoi un composant séparé de {@link UsageLedgerService} : la garde qui avale les erreurs
 * doit se trouver <b>en dehors</b> de la frontière transactionnelle. Placée à l'intérieur, elle
 * attraperait bien l'exception, mais la transaction resterait marquée {@code rollback-only} et
 * relèverait au moment du commit — c'est-à-dire après la garde, chez l'appelant, dont on veut
 * précisément qu'il ne soit jamais interrompu par un relevé.</p>
 *
 * <p>{@code REQUIRES_NEW} pour la même raison : le relevé est une observation, pas une partie du
 * tour. Il ne doit pas entraîner la transaction appelante dans son échec.</p>
 */
@Component
class UsageTurnWriter {

    private final UsageTurnRepository usageTurnRepository;
    private final Clock clock;

    UsageTurnWriter(UsageTurnRepository usageTurnRepository, Clock clock) {
        this.usageTurnRepository = usageTurnRepository;
        this.clock = clock;
    }

    /**
     * Ajoute la ligne. Les volumes sont déjà normalisés par {@link UsageLedgerService}.
     *
     * @param tokens tokens du tour par nature ; {@code inputTokens} de la ligne reçoit le volume
     *               <b>traité</b> (cache compris), inchangé depuis F-61, et les deux colonnes de
     *               cache le ventilent
     * @param cost   coût réel du tour (F-133), ou {@code null} si aucun n'a pu être établi
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void write(UUID userId, UUID workspaceId, UUID hostId, TurnTokens tokens, TurnCost cost) {
        usageTurnRepository.save(UsageTurn.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .hostId(hostId)
                .inputTokens(tokens.processedInputTokens())
                .outputTokens(tokens.outputTokens())
                .cacheReadTokens(tokens.cacheReadTokens())
                .cacheWriteTokens(tokens.cacheWriteTokens())
                .providerCostUsd(cost == null ? null : cost.amountUsd())
                .costSource(cost == null ? null : cost.source())
                .model(cost == null ? null : cost.model())
                .pricingVersion(cost == null ? null : cost.pricingVersion())
                .pricingFallback(cost != null && cost.pricingFallback())
                .occurredAt(OffsetDateTime.now(clock))
                .build());
    }
}
