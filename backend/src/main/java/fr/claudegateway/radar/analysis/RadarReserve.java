package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.radar.RadarScope;

/**
 * <b>La réserve de synchro</b> d'un poste (F-101 / SF-101-05) : ce que l'analyse peut encore dépenser.
 *
 * <p>Une limite <b>propre au Radar</b> : la synchro ne mange jamais le quota des conversations (cadrage
 * §11). Depuis F-107 / SF-107-04, elle suit le <b>droit Vigie</b> ({@link VigieRadarReserve}) : 3 M par client
 * et par mois pour un abonné ({@link ConfiguredRadarReserve}), une réserve d'essai pour un essai par code,
 * rien sans droit ; la première synchro d'un client est hors réserve.</p>
 */
public interface RadarReserve {

    /**
     * Ce qui reste.
     *
     * @param remaining jetons encore disponibles (peut être négatif : un appel en cours a dépassé)
     * @param retryAt   quand la limite qui s'applique se renouvelle ; {@code null} si elle ne se renouvelle
     *                  pas d'elle-même (plafond d'une synchro)
     */
    record Availability(long remaining, OffsetDateTime retryAt) {

        public boolean exhausted() {
            return remaining <= 0;
        }
    }

    /**
     * La réserve d'un poste, telle que l'écran la montre.
     *
     * @param trial vrai si c'est la <b>réserve d'essai</b> de la Vigie (F-107 / SF-107-04) : une enveloppe pour
     *              tout le compte sur la durée de l'essai, sans renouvellement ({@code resetsAt} nul)
     */
    record ReserveView(long monthlyTokens, long consumedThisMonth, long remainingThisMonth, long perSyncTokens,
            OffsetDateTime resetsAt, boolean trial) {

        /** Réserve mensuelle d'un abonné. */
        public ReserveView(long monthlyTokens, long consumedThisMonth, long remainingThisMonth, long perSyncTokens,
                OffsetDateTime resetsAt) {
            this(monthlyTokens, consumedThisMonth, remainingThisMonth, perSyncTokens, resetsAt, false);
        }
    }

    /** Ce qui reste pour cette synchro de ce poste. */
    Availability available(RadarScope scope, UUID syncId, OffsetDateTime now);

    /** La réserve mensuelle du poste. */
    ReserveView view(RadarScope scope, OffsetDateTime now);
}
