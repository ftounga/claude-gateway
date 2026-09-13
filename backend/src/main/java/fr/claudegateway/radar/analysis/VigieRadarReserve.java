package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.access.AccessGrant;
import fr.claudegateway.access.AccessGrantService;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.radar.RadarSyncRepository;

/**
 * La réserve de synchro <b>portée par le droit Vigie</b> (F-107 / SF-107-04, cadrage §9).
 *
 * <ul>
 *   <li><b>Abonné</b> — plan qui inclut la Vigie, option Vigie en cours, ou administrateur : <b>3 M jetons par
 *       client suivi et par mois civil</b> ({@link ConfiguredRadarReserve}), renouvelés le 1ᵉʳ ;</li>
 *   <li><b>essai Vigie par code d'accès</b> (seule source du droit) : une <b>réserve d'essai</b> unique pour
 *       tout le compte ({@code app.radar.reserve.trial-tokens}), comptée depuis la consommation du code, sans
 *       renouvellement — l'essai mesure un client suivi, et par poste un essai à cinq postes offrirait 15 M ;</li>
 *   <li><b>aucun droit</b> : réserve nulle, l'analyse s'arrête proprement.</li>
 * </ul>
 *
 * <p><b>La première synchro d'un client est hors réserve</b>, essai ou non : elle n'entre dans aucune somme
 * et n'est bornée que par le plafond par synchro. La réserve des conversations n'est jamais touchée.</p>
 *
 * <p>Isolation : toute somme porte {@code user_id} (et {@code host_id} pour la réserve mensuelle).</p>
 */
@Component
@Transactional(readOnly = true)
public class VigieRadarReserve implements RadarReserve {

    private final RadarSyncRepository syncs;
    private final RadarReserveProperties properties;
    private final SpaceEntitlementService entitlements;
    private final AccessGrantService grants;
    private final ConfiguredRadarReserve subscriberReserve;

    public VigieRadarReserve(RadarSyncRepository syncs, RadarReserveProperties properties,
            SpaceEntitlementService entitlements, AccessGrantService grants) {
        this.syncs = syncs;
        this.properties = properties;
        this.entitlements = entitlements;
        this.grants = grants;
        this.subscriberReserve = new ConfiguredRadarReserve(syncs, properties);
    }

    @Override
    public Availability available(RadarScope scope, UUID syncId, OffsetDateTime now) {
        RadarSync sync = syncId == null ? null
                : syncs.findByIdAndUserIdAndHostId(syncId, scope.userId(), scope.hostId()).orElse(null);
        if (sync != null && sync.isReserveExempt()) {
            // La première synchro d'un client : hors réserve, quel que soit le droit.
            return subscriberReserve.available(scope, syncId, now);
        }
        if (entitlements.isEntitledBySubscription(scope.userId(), EntitlementSpace.VIGIE)) {
            return subscriberReserve.available(scope, syncId, now);
        }
        Optional<AccessGrant> trial = trial(scope.userId());
        if (trial.isEmpty()) {
            return new Availability(0, null);
        }
        return subscriberReserve.capped(sync, trialRemaining(scope.userId(), trial.get(), now), null);
    }

    @Override
    public ReserveView view(RadarScope scope, OffsetDateTime now) {
        if (entitlements.isEntitledBySubscription(scope.userId(), EntitlementSpace.VIGIE)) {
            return subscriberReserve.view(scope, now);
        }
        Optional<AccessGrant> trial = trial(scope.userId());
        if (trial.isEmpty()) {
            return new ReserveView(0, 0, 0, properties.perSyncTokens(), null, false);
        }
        long consumed = syncs.sumAccountConsumedSince(scope.userId(), trialStart(trial.get(), now));
        return new ReserveView(properties.trialTokens(), consumed, Math.max(0, properties.trialTokens() - consumed),
                properties.perSyncTokens(), null, true);
    }

    private Optional<AccessGrant> trial(UUID userId) {
        return grants.grantWithGrace(userId, EntitlementSpace.VIGIE);
    }

    private long trialRemaining(UUID userId, AccessGrant trial, OffsetDateTime now) {
        return properties.trialTokens() - syncs.sumAccountConsumedSince(userId, trialStart(trial, now));
    }

    /** Début de l'essai : la consommation du code ; à défaut (code d'avant F-107 sans trace), le mois civil. */
    private static OffsetDateTime trialStart(AccessGrant trial, OffsetDateTime now) {
        return trial.redeemedAt() != null ? trial.redeemedAt() : RadarReserveProperties.monthStart(now);
    }
}
