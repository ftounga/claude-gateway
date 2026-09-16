package fr.claudegateway.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.runner.host.RunnerHostNotFoundException;
import fr.claudegateway.runner.host.RunnerHostService;

/** Le cœur métier du suivi d'activité (F-124 / SF-124-01) : TJM par poste et mois de départ. */
@ExtendWith(MockitoExtension.class)
class ActivityConfigServiceTest {

    @Mock private PosteBillingRepository billingRepository;
    @Mock private ActivitySettingsRepository settingsRepository;
    @Mock private RunnerHostService hostService;

    @InjectMocks private ActivityConfigService service;

    private UUID userId;
    private UUID hostId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        hostId = UUID.randomUUID();
    }

    // -------------------------------------------------------------------- mois de départ

    @Test
    void startMonth_defaultsToSeptember2025_whenNoSettings() {
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.empty());
        assertThat(service.startMonth(userId)).isEqualTo(YearMonth.of(2025, 9));
    }

    @Test
    void startMonth_readsTheStoredValue() {
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.of(
                ActivitySettings.builder().userId(userId).startMonth("2026-01").build()));
        assertThat(service.startMonth(userId)).isEqualTo(YearMonth.of(2026, 1));
    }

    @Test
    void setStartMonth_createsWhenAbsent() {
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.empty());
        YearMonth result = service.setStartMonth(userId, "2025-09");
        assertThat(result).isEqualTo(YearMonth.of(2025, 9));
        verify(settingsRepository).save(any(ActivitySettings.class));
    }

    @Test
    void setStartMonth_updatesWhenPresent() {
        ActivitySettings existing = ActivitySettings.builder().userId(userId).startMonth("2025-09").build();
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.of(existing));
        service.setStartMonth(userId, "2026-03");
        assertThat(existing.getStartMonth()).isEqualTo("2026-03");
        verify(settingsRepository, never()).save(any());
    }

    @Test
    void setStartMonth_refusesMalformed() {
        assertThatThrownBy(() -> service.setStartMonth(userId, "2025/09"))
                .isInstanceOf(InvalidActivityConfigException.class);
        assertThatThrownBy(() -> service.setStartMonth(userId, "2025-13"))
                .isInstanceOf(InvalidActivityConfigException.class);
        assertThatThrownBy(() -> service.setStartMonth(userId, "nope"))
                .isInstanceOf(InvalidActivityConfigException.class);
        verify(settingsRepository, never()).save(any());
    }

    // -------------------------------------------------------------------- TJM

    @Test
    void setRate_createsWhenAbsent_afterOwnershipCheck() {
        when(billingRepository.findByUserIdAndHostId(userId, hostId)).thenReturn(Optional.empty());
        when(billingRepository.save(any(PosteBilling.class))).thenAnswer(inv -> inv.getArgument(0));

        PosteBilling saved = service.setRate(userId, hostId, 55_000L);

        verify(hostService).requireOwned(userId, hostId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getHostId()).isEqualTo(hostId);
        assertThat(saved.getDailyRateCents()).isEqualTo(55_000L);
    }

    @Test
    void setRate_updatesWhenPresent() {
        PosteBilling existing = PosteBilling.builder().userId(userId).hostId(hostId)
                .dailyRateCents(50_000L).build();
        when(billingRepository.findByUserIdAndHostId(userId, hostId)).thenReturn(Optional.of(existing));

        PosteBilling saved = service.setRate(userId, hostId, 60_000L);

        assertThat(saved.getDailyRateCents()).isEqualTo(60_000L);
        verify(billingRepository, never()).save(any());
    }

    @Test
    void setRate_refusesNegativeAndOutOfBounds() {
        assertThatThrownBy(() -> service.setRate(userId, hostId, -1L))
                .isInstanceOf(InvalidActivityConfigException.class);
        assertThatThrownBy(() -> service.setRate(userId, hostId, PosteBilling.MAX_DAILY_RATE_CENTS + 1))
                .isInstanceOf(InvalidActivityConfigException.class);
        verify(billingRepository, never()).save(any());
    }

    @Test
    void setRate_propagatesNotFound_forSomeoneElsesHost() {
        // Isolation : requireOwned lève pour un poste d'autrui (ou inexistant) → rien écrit.
        org.mockito.Mockito.doThrow(new RunnerHostNotFoundException("Poste introuvable"))
                .when(hostService).requireOwned(userId, hostId);
        assertThatThrownBy(() -> service.setRate(userId, hostId, 55_000L))
                .isInstanceOf(RunnerHostNotFoundException.class);
        verify(billingRepository, never()).findByUserIdAndHostId(any(), any());
        verify(billingRepository, never()).save(any());
    }

    @Test
    void ratesByHost_mapsRatesByHostId() {
        UUID other = UUID.randomUUID();
        when(billingRepository.findByUserId(userId)).thenReturn(List.of(
                PosteBilling.builder().userId(userId).hostId(hostId).dailyRateCents(55_000L).build(),
                PosteBilling.builder().userId(userId).hostId(other).dailyRateCents(70_000L).build()));

        assertThat(service.ratesByHost(userId))
                .containsEntry(hostId, 55_000L)
                .containsEntry(other, 70_000L);
    }

    @Test
    void clearRate_deletesWhenPresent_andIsIdempotent() {
        PosteBilling existing = PosteBilling.builder().userId(userId).hostId(hostId)
                .dailyRateCents(55_000L).build();
        when(billingRepository.findByUserIdAndHostId(userId, hostId))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.empty());

        service.clearRate(userId, hostId);
        verify(billingRepository).delete(existing);

        service.clearRate(userId, hostId); // rien à retirer : pas d'erreur
        verify(billingRepository, times(1)).delete(any()); // supprimé une seule fois
    }
}
