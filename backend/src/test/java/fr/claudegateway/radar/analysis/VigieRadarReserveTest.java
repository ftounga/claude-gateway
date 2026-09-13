package fr.claudegateway.radar.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.access.AccessGrant;
import fr.claudegateway.access.AccessGrantService;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.radar.RadarSyncRepository;

/**
 * F-107 / SF-107-04 — <b>la réserve de synchro suit le droit Vigie</b> : 3 M par client et par mois pour un
 * abonné, une réserve d'essai pour le compte pendant l'essai, rien sans droit ; la première synchro hors réserve.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VigieRadarReserveTest {

    @Mock private RadarSyncRepository syncs;
    @Mock private SpaceEntitlementService entitlements;
    @Mock private AccessGrantService grants;

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 20, 10, 0, 0, 0, ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final RadarScope scope = new RadarScope(userId, UUID.randomUUID());
    private final UUID syncId = UUID.randomUUID();

    private VigieRadarReserve reserve;

    @BeforeEach
    void setUp() {
        reserve = new VigieRadarReserve(syncs, new RadarReserveProperties(3_000_000L, 0L, 3_000_000L), entitlements,
                grants);
        when(syncs.findByIdAndUserIdAndHostId(syncId, userId, scope.hostId()))
                .thenReturn(Optional.of(RadarSync.builder().id(syncId).userId(userId).hostId(scope.hostId())
                        .reserveExempt(false).build()));
    }

    private void exemptSync() {
        when(syncs.findByIdAndUserIdAndHostId(syncId, userId, scope.hostId()))
                .thenReturn(Optional.of(RadarSync.builder().id(syncId).userId(userId).hostId(scope.hostId())
                        .reserveExempt(true).build()));
    }

    @Test
    @DisplayName("Abonné : 3 M par poste et par mois, renouvelés le 1er")
    void subscriberGetsTheMonthlyReservePerHost() {
        when(entitlements.isEntitledBySubscription(userId, EntitlementSpace.VIGIE)).thenReturn(true);
        when(syncs.sumConsumedSince(eq(userId), eq(scope.hostId()), any())).thenReturn(1_000_000L);

        RadarReserve.Availability availability = reserve.available(scope, syncId, NOW);

        assertThat(availability.remaining()).isEqualTo(2_000_000L);
        assertThat(availability.retryAt()).isEqualTo(RadarReserveProperties.nextMonthStart(NOW));
        assertThat(reserve.view(scope, NOW).trial()).isFalse();
        verify(syncs, never()).sumAccountConsumedSince(any(), any());
    }

    @Test
    @DisplayName("Essai Vigie : 3 M pour tout le compte depuis la consommation du code, sans renouvellement")
    void trialGetsTheAccountTrialReserve() {
        OffsetDateTime redeemedAt = NOW.minusDays(3);
        when(grants.grantWithGrace(userId, EntitlementSpace.VIGIE)).thenReturn(Optional.of(new AccessGrant(
                PlanCode.GOLD, NOW.plusDays(11), null, "essai", EntitlementSpace.VIGIE, redeemedAt)));
        when(syncs.sumAccountConsumedSince(userId, redeemedAt)).thenReturn(2_500_000L);

        RadarReserve.Availability availability = reserve.available(scope, syncId, NOW);
        assertThat(availability.remaining()).isEqualTo(500_000L);
        assertThat(availability.retryAt()).as("une réserve d'essai ne se renouvelle pas").isNull();

        RadarReserve.ReserveView view = reserve.view(scope, NOW);
        assertThat(view.trial()).isTrue();
        assertThat(view.monthlyTokens()).isEqualTo(3_000_000L);
        assertThat(view.consumedThisMonth()).isEqualTo(2_500_000L);
        assertThat(view.resetsAt()).isNull();
    }

    @Test
    @DisplayName("Essai épuisé : la réserve le dit, sans date de reprise")
    void exhaustedTrial() {
        OffsetDateTime redeemedAt = NOW.minusDays(3);
        when(grants.grantWithGrace(userId, EntitlementSpace.VIGIE)).thenReturn(Optional.of(new AccessGrant(
                PlanCode.GOLD, NOW.plusDays(11), null, "essai", EntitlementSpace.VIGIE, redeemedAt)));
        when(syncs.sumAccountConsumedSince(userId, redeemedAt)).thenReturn(3_200_000L);

        assertThat(reserve.available(scope, syncId, NOW).exhausted()).isTrue();
    }

    @Test
    @DisplayName("Aucun droit Vigie : réserve nulle")
    void noRightNoReserve() {
        when(grants.grantWithGrace(userId, EntitlementSpace.VIGIE)).thenReturn(Optional.empty());

        assertThat(reserve.available(scope, syncId, NOW).exhausted()).isTrue();
        assertThat(reserve.view(scope, NOW).remainingThisMonth()).isZero();
    }

    @Test
    @DisplayName("Première synchro : hors réserve, même un essai épuisé ne l'arrête pas")
    void exemptSyncIsNeverStoppedByAReserve() {
        exemptSync();
        when(grants.grantWithGrace(userId, EntitlementSpace.VIGIE)).thenReturn(Optional.empty());

        RadarReserve.Availability availability = reserve.available(scope, syncId, NOW);

        assertThat(availability.exhausted()).isFalse();
        assertThat(availability.remaining()).isGreaterThan(1_000_000_000L);
    }

    @Test
    @DisplayName("Le plafond par synchro reste appliqué, même à une synchro hors réserve")
    void perSyncCeilingStillApplies() {
        reserve = new VigieRadarReserve(syncs, new RadarReserveProperties(3_000_000L, 200_000L, 3_000_000L),
                entitlements, grants);
        when(syncs.findByIdAndUserIdAndHostId(syncId, userId, scope.hostId()))
                .thenReturn(Optional.of(RadarSync.builder().id(syncId).userId(userId).hostId(scope.hostId())
                        .reserveExempt(true).consumedTokens(150_000L).build()));

        assertThat(reserve.available(scope, syncId, NOW).remaining()).isEqualTo(50_000L);
    }
}
