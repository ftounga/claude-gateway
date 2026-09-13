package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSyncRepository;

/**
 * La réserve de synchro <b>configurée</b> (F-101 / SF-101-05) : un budget mensuel par poste, et un
 * plafond facultatif par synchro. Toute somme porte {@code user_id} et {@code host_id} : la
 * consommation d'un autre poste du même utilisateur n'entame jamais celle-ci.
 */
@Component
@Transactional(readOnly = true)
public class ConfiguredRadarReserve implements RadarReserve {

    private final RadarSyncRepository syncs;
    private final RadarReserveProperties properties;

    public ConfiguredRadarReserve(RadarSyncRepository syncs, RadarReserveProperties properties) {
        this.syncs = syncs;
        this.properties = properties;
    }

    @Override
    public Availability available(RadarScope scope, UUID syncId, OffsetDateTime now) {
        long monthly = properties.monthlyTokens()
                - syncs.sumConsumedSince(scope.userId(), scope.hostId(), RadarReserveProperties.monthStart(now));
        if (properties.perSyncTokens() > 0 && syncId != null) {
            long consumed = syncs.findByIdAndUserIdAndHostId(syncId, scope.userId(), scope.hostId())
                    .map(s -> s.getConsumedTokens()).orElse(0L);
            long perSync = properties.perSyncTokens() - consumed;
            if (perSync < monthly) {
                return new Availability(perSync, null);
            }
        }
        return new Availability(monthly, RadarReserveProperties.nextMonthStart(now));
    }

    @Override
    public ReserveView view(RadarScope scope, OffsetDateTime now) {
        long consumed = syncs.sumConsumedSince(scope.userId(), scope.hostId(), RadarReserveProperties.monthStart(now));
        return new ReserveView(properties.monthlyTokens(), consumed, Math.max(0, properties.monthlyTokens() - consumed),
                properties.perSyncTokens(), RadarReserveProperties.nextMonthStart(now));
    }
}
