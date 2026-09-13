package fr.claudegateway.runner.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Les règles des espaces d'un client (F-106 / SF-106-01), sans base. */
@ExtendWith(MockitoExtension.class)
class HostSpaceServiceTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID HOST = UUID.randomUUID();

    @Mock private HostSpaceRepository repository;
    @Mock private RunnerHostService hostService;
    @Mock private fr.claudegateway.billing.seat.SeatLedgerService seatLedger;
    @InjectMocks private HostSpaceService service;

    private static HostSpace row(ClientSpace space) {
        return HostSpace.builder().id(UUID.randomUUID()).userId(ALICE).hostId(HOST).space(space)
                .activatedAt(OffsetDateTime.now()).build();
    }

    @Test
    void aHostWithoutAnyRowIsReadAsForge() {
        when(repository.findByUserIdAndHostId(ALICE, HOST)).thenReturn(List.of());
        assertThat(service.spacesOf(ALICE, HOST)).containsExactly(ClientSpace.FORGE);
        verify(hostService).requireOwned(ALICE, HOST);
    }

    @Test
    void activatingMaterializesTheImplicitForgeRowFirst() {
        when(repository.findByUserIdAndHostId(ALICE, HOST)).thenReturn(List.of());
        Set<ClientSpace> spaces = service.activate(ALICE, HOST, ClientSpace.VIGIE);
        assertThat(spaces).containsExactly(ClientSpace.FORGE, ClientSpace.VIGIE);
        ArgumentCaptor<HostSpace> saved = ArgumentCaptor.forClass(HostSpace.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(HostSpace::getSpace)
                .containsExactly(ClientSpace.FORGE, ClientSpace.VIGIE);
        assertThat(saved.getAllValues()).allMatch(r -> r.getUserId().equals(ALICE));
    }

    @Test
    void activatingTwiceWritesNothingMore() {
        when(repository.findByUserIdAndHostId(ALICE, HOST))
                .thenReturn(List.of(row(ClientSpace.FORGE), row(ClientSpace.VIGIE)));
        assertThat(service.activate(ALICE, HOST, ClientSpace.VIGIE))
                .containsExactly(ClientSpace.FORGE, ClientSpace.VIGIE);
        verify(repository, never()).save(any());
    }

    @Test
    void removingASpaceKeepsTheOther() {
        when(repository.findByUserIdAndHostId(ALICE, HOST))
                .thenReturn(List.of(row(ClientSpace.FORGE), row(ClientSpace.VIGIE)));
        when(hostService.requireOwned(ALICE, HOST)).thenReturn(RunnerHost.builder().id(HOST).userId(ALICE)
                .missionStatus(HostMissionStatus.ACTIVE).build());
        assertThat(service.remove(ALICE, HOST, ClientSpace.VIGIE)).containsExactly(ClientSpace.FORGE);
        verify(repository).deleteOne(ALICE, HOST, ClientSpace.VIGIE);
        // F-107 / SF-107-05 : le mois engagé dans la Vigie reste dû.
        verify(seatLedger).noteSpaceRemoval(org.mockito.ArgumentMatchers.eq(ALICE), org.mockito.ArgumentMatchers.eq(HOST),
                org.mockito.ArgumentMatchers.eq(fr.claudegateway.billing.EntitlementSpace.VIGIE), any());
    }

    @Test
    void theLastSpaceIsNeverRemoved() {
        when(repository.findByUserIdAndHostId(ALICE, HOST)).thenReturn(List.of(row(ClientSpace.VIGIE)));
        assertThatThrownBy(() -> service.remove(ALICE, HOST, ClientSpace.VIGIE))
                .isInstanceOf(HostLastSpaceException.class);
        verify(repository, never()).deleteOne(any(), any(), any());
    }

    @Test
    void removingTheImplicitForgeOfAHostWithoutRowIsRefused() {
        when(repository.findByUserIdAndHostId(ALICE, HOST)).thenReturn(List.of());
        assertThatThrownBy(() -> service.remove(ALICE, HOST, ClientSpace.FORGE))
                .isInstanceOf(HostLastSpaceException.class);
    }

    @Test
    void removingASpaceWhereTheHostIsNotChangesNothing() {
        when(repository.findByUserIdAndHostId(ALICE, HOST)).thenReturn(List.of(row(ClientSpace.FORGE)));
        assertThat(service.remove(ALICE, HOST, ClientSpace.VIGIE)).containsExactly(ClientSpace.FORGE);
        verify(repository, never()).deleteOne(any(), any(), any());
    }

    @Test
    void anUnownedHostStopsEveryGestureBeforeAnyWrite() {
        when(hostService.requireOwned(ALICE, HOST)).thenThrow(new RunnerHostNotFoundException("x"));
        assertThatThrownBy(() -> service.activate(ALICE, HOST, ClientSpace.VIGIE))
                .isInstanceOf(RunnerHostNotFoundException.class);
        verify(repository, never()).save(any());
        verify(repository, never()).findByUserIdAndHostId(any(), any());
    }

    @Test
    void requireActiveRefusesAHostOutsideTheSpace() {
        when(repository.findByUserIdAndHostId(ALICE, HOST)).thenReturn(List.of(row(ClientSpace.FORGE)));
        assertThatThrownBy(() -> service.requireActive(ALICE, HOST, ClientSpace.VIGIE))
                .isInstanceOf(HostNotInSpaceException.class);
    }

    @Test
    void spacesInFallsBackToForge() {
        UUID other = UUID.randomUUID();
        Map<UUID, Set<ClientSpace>> byHost = Map.of(HOST, Set.of(ClientSpace.VIGIE));
        assertThat(HostSpaceService.spacesIn(byHost, HOST)).containsExactly(ClientSpace.VIGIE);
        assertThat(HostSpaceService.spacesIn(byHost, other)).containsExactly(ClientSpace.FORGE);
    }

    @Test
    void creationWritesTheChosenSpaceOnly() {
        RunnerHost host = RunnerHost.builder().id(HOST).userId(ALICE).name("EDENRED").build();
        when(hostService.create(ALICE, "EDENRED")).thenReturn(host);
        service.createHost(ALICE, "EDENRED", ClientSpace.VIGIE);
        ArgumentCaptor<HostSpace> saved = ArgumentCaptor.forClass(HostSpace.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSpace()).isEqualTo(ClientSpace.VIGIE);
        assertThat(saved.getValue().getHostId()).isEqualTo(HOST);
    }

    @Test
    void deletionEventPurgesTheHostRows() {
        service.onHostLifecycle(RunnerHostLifecycleEvent.deleted(ALICE, HOST));
        verify(repository).deleteByUserIdAndHostId(ALICE, HOST);
    }

    @Test
    void creationEventWritesNothing() {
        service.onHostLifecycle(RunnerHostLifecycleEvent.created(ALICE, HOST));
        verify(repository, never()).deleteByUserIdAndHostId(any(), any());
    }

    @Test
    void parseIsCaseInsensitiveAndDefaultsToForge() {
        assertThat(ClientSpace.parse(null)).isEqualTo(ClientSpace.FORGE);
        assertThat(ClientSpace.parse(" vigie ")).isEqualTo(ClientSpace.VIGIE);
        assertThatThrownBy(() -> ClientSpace.parse("atelier"))
                .isInstanceOf(InvalidClientSpaceException.class);
    }
}
