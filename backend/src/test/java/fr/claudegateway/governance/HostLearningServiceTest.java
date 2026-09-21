package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.governance.dto.HostLearningView;
import fr.claudegateway.runner.audit.RunnerAuditRepository;

/**
 * La mesure qui décide si l'application apprend (F-140 / SF-140-01).
 *
 * <p>Deux façons de mentir avec ce chiffre, et les deux sont évitées ici : <b>diviser par zéro</b>
 * (rendre « 0,0 appel par tour » quand il ne s'est rien passé), et <b>compter large</b> (mêler les
 * appels d'un autre poste ou d'un autre compte).</p>
 */
class HostLearningServiceTest {

    private final RunnerAuditRepository audits = mock(RunnerAuditRepository.class);
    private final AtelierMessageRepository messages = mock(AtelierMessageRepository.class);
    private final GovernanceHostScope hostScope = mock(GovernanceHostScope.class);
    private final GovernanceMapGrowthRepository growth = mock(GovernanceMapGrowthRepository.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(hostId);

    private HostLearningService service;

    @BeforeEach
    void setUp() {
        service = new HostLearningService(audits, messages, hostScope, growth,
                Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC));
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        when(hostScope.projectsOf(userId, host)).thenReturn(List.of(workspace));
        when(growth.findByUserIdAndHostId(userId, hostId)).thenReturn(List.of());
    }

    @Test
    @DisplayName("rend les appels par tour sur les deux fenêtres")
    void rendersCallsPerTurnOnBothWindows() {
        when(messages.countByUserIdAndWorkspaceIdInAndRoleAndCreatedAtGreaterThanEqual(
                eq(userId), any(), eq("USER"), any())).thenReturn(10L, 40L);
        when(audits.countByUserIdAndHostIdAndCreatedAtGreaterThanEqual(eq(userId), eq(hostId), any()))
                .thenReturn(45L, 320L);

        HostLearningView view = service.describe(userId, host);

        // 45 appels pour 10 tours récents, 320 pour 40 tours sur la longue fenêtre : l'agent cherche
        // moins qu'avant, et c'est exactement ce que la mesure doit rendre lisible.
        assertThat(view.recentCallsPerTurn()).isEqualTo(4.5);
        assertThat(view.longCallsPerTurn()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("LE CRITÈRE : sans tour, aucun ratio — jamais zéro")
    void withoutTurnsThereIsNoRatio() {
        when(messages.countByUserIdAndWorkspaceIdInAndRoleAndCreatedAtGreaterThanEqual(
                eq(userId), any(), anyString(), any())).thenReturn(0L);
        when(audits.countByUserIdAndHostIdAndCreatedAtGreaterThanEqual(any(), any(), any()))
                .thenReturn(0L);

        HostLearningView view = service.describe(userId, host);

        assertThat(view.recentCallsPerTurn()).isNull();
        assertThat(view.longCallsPerTurn()).isNull();
    }

    @Test
    @DisplayName("un poste sans projet ne déclenche aucune requête de comptage de tours")
    void ahostWithoutProjectsCountsNothing() {
        // Sans cette garde, la requête partirait avec un « IN () » vide.
        when(hostScope.projectsOf(userId, host)).thenReturn(List.of());

        service.describe(userId, host);

        verify(messages, never()).countByUserIdAndWorkspaceIdInAndRoleAndCreatedAtGreaterThanEqual(
                any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("le poste « Hébergé » n'a aucun appel de poste à compter")
    void thehostedWorkspaceHasNothingToCount() {
        HostLearningView view = service.describe(userId, new GovernanceHostRef(null, true));

        assertThat(view.recentCalls()).isZero();
        assertThat(view.recentCallsPerTurn()).isNull();
        verify(audits, never()).countByUserIdAndHostIdAndCreatedAtGreaterThanEqual(any(), any(), any());
    }

    @Test
    @DisplayName("les comptages portent sur CE poste et CE compte")
    void countsAreScopedToThisHostAndThisAccount() {
        service.describe(userId, host);

        verify(audits, org.mockito.Mockito.atLeastOnce())
                .countByUserIdAndHostIdAndCreatedAtGreaterThanEqual(eq(userId), eq(hostId),
                        any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("les faits de la carte remontent avec la mesure")
    void factsTravelWithTheMeasure() {
        when(growth.findByUserIdAndHostId(userId, hostId)).thenReturn(List.of(
                GovernanceMapGrowth.builder().userId(userId).hostId(hostId).path("acces.md")
                        .facts(641).build()));

        assertThat(service.describe(userId, host).facts()).isEqualTo(641);
    }
}
