package fr.claudegateway.radar;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fr.claudegateway.radar.sync.RadarHostSettingsRepository;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.runner.relay.RunnerCallRouter;

/**
 * Socle des tests d'intégration de la synchro du soir (F-100) : le Radar de F-99, et un <b>runner
 * simulé</b> — le routeur d'appels est remplacé, aucun poste réel n'est joint.
 */
abstract class RadarSyncIntegrationTestBase extends RadarIntegrationTestBase {

    @MockitoBean protected RunnerCallRouter router;

    @Autowired protected RadarHostSettingsRepository hostSettings;
    @Autowired protected RunnerAuditRepository runnerAudits;

    @Override
    protected void cleanRadarTables() {
        super.cleanRadarTables();
        hostSettings.deleteAll();
        runnerAudits.deleteAll();
    }
}
