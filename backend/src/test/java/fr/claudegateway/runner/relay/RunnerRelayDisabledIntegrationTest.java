package fr.claudegateway.runner.relay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * T6 (F-38 / SF-38-12) : <b>secret vide ⇒ le relais n'existe pas</b>.
 *
 * <p>C'est la configuration par défaut — dev, tests, et production tant que
 * {@code APP_RUNNER_RELAY_SECRET} n'est pas renseigné. Aucun connecteur supplémentaire n'est ouvert,
 * aucun contrôleur interne n'est publié, aucun client sortant n'existe : la surface est nulle, et le
 * comportement reste exactement celui d'avant SF-38-12. Il n'y a <b>jamais</b> de repli non
 * authentifié.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class RunnerRelayDisabledIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void noRelayBeanIsPublishedWithoutASecret() {
        assertThat(context.getBeansOfType(RunnerRelayController.class)).isEmpty();
        assertThat(context.getBeansOfType(RunnerRelayConnectorCustomizer.class)).isEmpty();
        assertThat(context.getBeansOfType(RunnerRelayClient.class)).isEmpty();
        assertThat(context.getBeansOfType(RelaySecurityConfig.class)).isEmpty();
        // F-38 / SF-38-13 : les routes d'interruption suivent la même règle — pas de secret, pas de
        // surface.
        assertThat(context.getBeansOfType(AtelierRelayController.class)).isEmpty();
    }

    @Test
    void theBroadcasterExistsButTalksToNoOne() {
        // Il est toujours câblé (les services appelants n'ont qu'un chemin de code), et reste inerte
        // tant que le relais n'est pas configuré : comportement strictement mono-pod.
        RunnerRelayBroadcaster broadcaster = context.getBean(RunnerRelayBroadcaster.class);

        assertThat(broadcaster.broadcastConfirm(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "toolu_1", true, null)).isFalse();
        assertThat(context.getBean(RelayPeerResolver.class).peerBaseUrls()).isEmpty();
    }

    @Test
    void relayPropertiesAreStillBoundAndReportDisabled() {
        RunnerRelayProperties properties = context.getBean(RunnerRelayProperties.class);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.selfBaseUrl()).isEmpty();
    }
}
