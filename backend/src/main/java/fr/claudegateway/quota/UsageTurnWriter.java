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

    /** Ajoute la ligne. Les volumes sont déjà normalisés par {@link UsageLedgerService}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void write(UUID userId, UUID workspaceId, UUID hostId, long inputTokens, long outputTokens) {
        usageTurnRepository.save(UsageTurn.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .hostId(hostId)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .occurredAt(OffsetDateTime.now(clock))
                .build());
    }
}
