package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.util.UUID;

import fr.claudegateway.radar.RadarScope;

/**
 * <b>La réserve de synchro</b> d'un poste (F-101 / SF-101-05) : ce que l'analyse peut encore dépenser.
 *
 * <p>Une limite <b>propre au Radar</b> : la synchro ne mange jamais le quota des conversations (cadrage
 * §11). L'implémentation par défaut est configurée ({@link ConfiguredRadarReserve}) ; l'option Vigie
 * (F-107 / SF-107-04) la remplacera sans toucher à l'arrêt propre de l'analyse.</p>
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

    /** La réserve d'un poste, telle que l'écran la montre. */
    record ReserveView(long monthlyTokens, long consumedThisMonth, long remainingThisMonth, long perSyncTokens,
            OffsetDateTime resetsAt) {
    }

    /** Ce qui reste pour cette synchro de ce poste. */
    Availability available(RadarScope scope, UUID syncId, OffsetDateTime now);

    /** La réserve mensuelle du poste. */
    ReserveView view(RadarScope scope, OffsetDateTime now);
}
