package fr.claudegateway.atelier.autonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.push.PushEvent;
import fr.claudegateway.quota.UsageTurnRepository;

/** F-219 / SF-219-02 — l'estimation affichée au lancement, et l'événement notifié par issue. */
class AutonomyServiceTest {

    private AutonomyService service;
    private UsageTurnRepository usage;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AutonomyTurnRunner runner = mock(AutonomyTurnRunner.class);
        when(runner.maxTurns()).thenReturn(8);
        service = new AutonomyService(null, null, null, null, null, runner, null, Clock.systemUTC(), 4,
                Duration.ofHours(3));
        usage = mock(UsageTurnRepository.class);
        service.setUsageTurns(usage);
    }

    @Test
    @DisplayName("avec des mesures : coût moyen × tours estimés (une étape ≈ un demi-tour)")
    void estimateWithMeasures() {
        when(usage.averageCostSince(eq(userId), any())).thenReturn(0.5);
        BigDecimal avg = service.averageTurnCost(userId);
        AutonomyService.Estimate estimate = service.estimate(avg, 5);
        assertThat(estimate.turns()).isEqualTo(3);
        assertThat(estimate.usd()).isEqualByComparingTo("1.50");
        assertThat(estimate.eur()).isEqualByComparingTo("1.38"); // F-224 / SF-224-05 : 1,50 $ × 0,92
        assertThat(estimate.basis()).contains("30 jours");
        assertThat(service.estimate(avg, 1).turns()).isEqualTo(1);
        assertThat(service.estimate(avg, 40).turns()).isEqualTo(8); // borné par le plafond
    }

    @Test
    @DisplayName("sans mesure, ou rien à faire : pas d'estimation")
    void noEstimate() {
        when(usage.averageCostSince(eq(userId), any())).thenReturn(null);
        assertThat(service.averageTurnCost(userId)).isNull();
        assertThat(service.estimate(null, 3)).isNull();
        assertThat(service.estimate(new BigDecimal("0.5"), 0)).isNull();
    }

    @Test
    @DisplayName("notifications : terminé, arrêté ; rien sur une reprise en main")
    void events() {
        assertThat(AutonomyNotifier.eventFor(AutonomyRunStatus.DONE, false)).isEqualTo(PushEvent.AUTONOMY_DONE);
        assertThat(AutonomyNotifier.eventFor(AutonomyRunStatus.BLOCKED, false)).isEqualTo(PushEvent.AUTONOMY_STOPPED);
        assertThat(AutonomyNotifier.eventFor(AutonomyRunStatus.FAILED, false)).isEqualTo(PushEvent.AUTONOMY_STOPPED);
        assertThat(AutonomyNotifier.eventFor(AutonomyRunStatus.STOPPED, true)).isNull();
        assertThat(PushEvent.AUTONOMY_STOPPED.requiresAction()).isTrue();
        assertThat(PushEvent.AUTONOMY_DONE.alwaysDelivered()).isTrue();
    }
}
