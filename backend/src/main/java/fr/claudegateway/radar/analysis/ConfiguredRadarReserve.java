package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.radar.RadarSyncRepository;

/**
 * La réserve de synchro <b>d'un abonné</b> (F-101 / SF-101-05) : un budget mensuel par poste, et un plafond
 * facultatif par synchro. Toute somme porte {@code user_id} et {@code host_id} : la consommation d'un autre
 * poste du même utilisateur n'entame jamais celle-ci.
 *
 * <p>Depuis F-107 / SF-107-04, ce n'est plus le composant servi : {@link VigieRadarReserve} choisit la réserve
 * selon le droit Vigie et délègue ici pour un abonné. <b>La première synchro d'un client est hors réserve</b> :
 * sa consommation n'entre dans aucune somme, et elle n'est bornée que par le plafond par synchro.</p>
 */
@Transactional(readOnly = true)
public class ConfiguredRadarReserve implements RadarReserve {

    /** Ce qui reste à une synchro hors réserve : pratiquement sans borne, sans jamais déborder un calcul. */
    static final long UNBOUNDED = Long.MAX_VALUE / 4;

    private final RadarSyncRepository syncs;
    private final RadarReserveProperties properties;

    public ConfiguredRadarReserve(RadarSyncRepository syncs, RadarReserveProperties properties) {
        this.syncs = syncs;
        this.properties = properties;
    }

    @Override
    public Availability available(RadarScope scope, UUID syncId, OffsetDateTime now) {
        RadarSync sync = syncId == null ? null
                : syncs.findByIdAndUserIdAndHostId(syncId, scope.userId(), scope.hostId()).orElse(null);
        long monthly = sync != null && sync.isReserveExempt()
                ? UNBOUNDED
                : properties.monthlyTokens()
                        - syncs.sumConsumedSince(scope.userId(), scope.hostId(), RadarReserveProperties.monthStart(now));
        return capped(sync, monthly, RadarReserveProperties.nextMonthStart(now));
    }

    @Override
    public ReserveView view(RadarScope scope, OffsetDateTime now) {
        long consumed = syncs.sumConsumedSince(scope.userId(), scope.hostId(), RadarReserveProperties.monthStart(now));
        return new ReserveView(properties.monthlyTokens(), consumed, Math.max(0, properties.monthlyTokens() - consumed),
                properties.perSyncTokens(), RadarReserveProperties.nextMonthStart(now));
    }

    /**
     * Applique le plafond par synchro, s'il est configuré et plus serré que la réserve.
     *
     * @param sync      la synchro, ou {@code null}
     * @param remaining ce que la réserve laisse
     * @param retryAt   renouvellement de la réserve, ou {@code null} si elle ne se renouvelle pas
     */
    Availability capped(RadarSync sync, long remaining, OffsetDateTime retryAt) {
        if (properties.perSyncTokens() > 0 && sync != null) {
            long perSync = properties.perSyncTokens() - sync.getConsumedTokens();
            if (perSync < remaining) {
                return new Availability(perSync, null);
            }
        }
        return new Availability(remaining, retryAt);
    }
}
