package fr.claudegateway.billing.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Le registre des <b>mois-postes</b> (F-65 / SF-65-01) — deux écritures, jamais de réécriture.
 *
 * <p>C'est ici que se joue la règle « un mois-poste se paie une fois » : la ligne posée par la
 * clôture est retrouvée par la réouverture, qui la laisse intacte. Ni piège (pas de double
 * facturation), ni faille (pas de compteur remis à zéro).</p>
 */
class SeatLedgerServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-03-16T10:15:00Z"), ZoneOffset.UTC);
    private static final LocalDate PERIOD = LocalDate.of(2026, 3, 1);

    private final HostSeatMonthRepository repository = mock(HostSeatMonthRepository.class);
    private final SeatLedgerService service = new SeatLedgerService(repository, CLOCK);

    private final UUID alice = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    @Test
    void closingASeatRecordsTheMonthItHadAlreadyEngaged() {
        when(repository.findByHostIdAndPeriodStart(hostId, PERIOD)).thenReturn(Optional.empty());

        service.noteClosure(alice, hostId, atStartOfDay(LocalDate.of(2025, 11, 4)));

        HostSeatMonth saved = captureSaved();
        assertThat(saved.getUserId()).isEqualTo(alice);
        assertThat(saved.getPeriodStart()).isEqualTo(PERIOD);
        // Poste antérieur au mois : il était facturable depuis le premier jour.
        assertThat(saved.getBillableFrom()).isEqualTo(PERIOD);
    }

    @Test
    void aSeatCreatedThisMonthIsOnlyEngagedFromItsCreation() {
        when(repository.findByHostIdAndPeriodStart(hostId, PERIOD)).thenReturn(Optional.empty());

        service.noteClosure(alice, hostId, atStartOfDay(LocalDate.of(2026, 3, 9)));

        assertThat(captureSaved().getBillableFrom()).isEqualTo(LocalDate.of(2026, 3, 9));
    }

    @Test
    void reopeningASeatNeverBilledThisMonthStartsFromToday() {
        when(repository.findByHostIdAndPeriodStart(hostId, PERIOD)).thenReturn(Optional.empty());

        service.noteReopening(alice, hostId);

        assertThat(captureSaved().getBillableFrom()).isEqualTo(LocalDate.of(2026, 3, 16));
    }

    @Test
    void reopeningASeatAlreadyCountedThisMonthWritesNothing() {
        when(repository.findByHostIdAndPeriodStart(hostId, PERIOD))
                .thenReturn(Optional.of(HostSeatMonth.builder()
                        .hostId(hostId)
                        .userId(alice)
                        .periodStart(PERIOD)
                        .billableFrom(PERIOD)
                        .build()));

        service.noteReopening(alice, hostId);

        verify(repository, never()).save(any());
    }

    @Test
    void closingTwiceInTheSameMonthWritesOnce() {
        when(repository.findByHostIdAndPeriodStart(hostId, PERIOD))
                .thenReturn(Optional.of(HostSeatMonth.builder()
                        .hostId(hostId)
                        .userId(alice)
                        .periodStart(PERIOD)
                        .billableFrom(PERIOD)
                        .build()));

        service.noteClosure(alice, hostId, atStartOfDay(LocalDate.of(2026, 3, 2)));

        verify(repository, never()).save(any());
    }

    @Test
    void deletingASeatForgetsItsMonths() {
        service.forgetHost(hostId);

        verify(repository).deleteByHostId(hostId);
    }

    private HostSeatMonth captureSaved() {
        ArgumentCaptor<HostSeatMonth> captor = ArgumentCaptor.forClass(HostSeatMonth.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static OffsetDateTime atStartOfDay(LocalDate date) {
        return date.atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
