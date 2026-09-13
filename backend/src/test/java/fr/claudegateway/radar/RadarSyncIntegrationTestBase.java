package fr.claudegateway.radar;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fr.claudegateway.radar.sync.RadarHostSettings;
import fr.claudegateway.radar.sync.RadarHostSettingsRepository;
import fr.claudegateway.radar.sync.RadarSyncLauncher;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.RunnerTokenRepository;
import fr.claudegateway.runner.RunnerTokenService;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.relay.RunnerCallRouter;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * Socle des tests d'intégration de la synchro du soir (F-100) : le Radar de F-99, et un <b>runner
 * simulé</b> — le routeur d'appels et la fraîcheur du battement sont remplacés, aucun poste réel n'est
 * joint.
 */
abstract class RadarSyncIntegrationTestBase extends RadarIntegrationTestBase {

    @MockitoBean protected RunnerCallRouter router;
    @MockitoBean protected RunnerLiveness liveness;
    /** Le droit Teams : accordé par défaut, retiré test par test. {@code requireAccess} laisse passer. */
    @MockitoBean protected TeamsAccessService teamsAccess;

    @BeforeEach
    void grantTeamsAccessByDefault() {
        when(teamsAccess.hasAccess(any(UUID.class))).thenReturn(true);
        when(teamsAccess.hasAccess()).thenReturn(true);
    }

    @Autowired protected RadarHostSettingsRepository hostSettings;
    @Autowired protected RunnerAuditRepository runnerAudits;
    @Autowired protected RunnerTokenRepository runnerTokens;
    @Autowired protected RunnerTokenService runnerTokenService;

    @Override
    protected void cleanRadarTables() {
        super.cleanRadarTables();
        hostSettings.deleteAll();
        runnerAudits.deleteAll();
        runnerTokens.deleteAll();
    }

    protected static RunnerCallResult ok(String json) {
        return new RunnerCallResult(true, json, false, null, 12L, null, null, null, "", false);
    }

    /** Le runner du poste accepte toute synchro. */
    protected void runnerAcceptsSyncs() {
        when(router.call(any(RunnerTarget.class), anyString(), eq(RadarSyncLauncher.COLLECT), any(), anyLong()))
                .thenReturn(ok("{\"accepted\":true}"));
    }

    /** Active le Radar d'un poste directement en base (heure, fuseau, dernier créneau traité). */
    protected RadarHostSettings enable(RadarScope scope, String time, String zone, LocalDate lastSlot) {
        RadarHostSettings row = hostSettings.findByUserIdAndHostId(scope.userId(), scope.hostId())
                .orElseGet(() -> RadarHostSettings.builder().userId(scope.userId()).hostId(scope.hostId()).build());
        row.setEnabled(true);
        row.setSyncTime(time);
        row.setTimeZone(zone);
        row.setLastSlotDate(lastSlot);
        row.setClientAuthorizedAt(java.time.OffsetDateTime.now());
        return hostSettings.save(row);
    }

    protected String runnerToken(RadarScope scope) {
        return runnerTokenService.issue(scope.userId(), scope.hostId(), "poste-" + UUID.randomUUID()).clearToken();
    }
}
