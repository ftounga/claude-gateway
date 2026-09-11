package fr.claudegateway.runner.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.billing.seat.SeatLedgerService;

/**
 * Cycle de vie d'un <b>poste</b> (F-48 / SF-48-01) : ce que l'utilisateur crée, et ce que le runner
 * déclare de sa machine à l'appairage.
 *
 * <p>Ces vérifications viennent en droite ligne de {@code WorkspaceService} : la racine, les droits
 * et l'interpréteur décrivaient déjà une <b>machine</b>, ils étaient simplement rangés sur le
 * projet. F-48 les met à leur place — et les tests suivent.</p>
 */
@ExtendWith(MockitoExtension.class)
class RunnerHostServiceTest {

    @Mock private RunnerHostRepository repository;
    @Mock private SeatLedgerService seatLedgerService;
    @Mock private fr.claudegateway.runner.RunnerTokenRepository tokenRepository;
    @Mock private fr.claudegateway.runner.RunnerPairingCodeRepository pairingCodeRepository;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private RunnerHostService service() {
        return new RunnerHostService(repository, seatLedgerService, tokenRepository,
                pairingCodeRepository);
    }

    @Test
    void createsAHostUnderTheFreeNameGivenByItsOwner() {
        when(repository.save(any(RunnerHost.class))).thenAnswer(inv -> inv.getArgument(0));

        RunnerHost host = service().create(alice, "  Poste CAGIP  ");

        assertThat(host.getName()).isEqualTo("Poste CAGIP");
        assertThat(host.getUserId()).isEqualTo(alice);
    }

    @Test
    void refusesAHostWithoutAName() {
        // Le nom est ce qui rend un poste reconnaissable dans une liste : il ne se devine pas.
        assertThatThrownBy(() -> service().create(alice, "   "))
                .isInstanceOf(InvalidHostNameException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void truncatesAnOverlongName() {
        when(repository.save(any(RunnerHost.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service().create(alice, "x".repeat(300)).getName()).hasSize(100);
    }

    @Test
    void aHostOfSomeoneElseIsIndistinguishableFromAnAbsentOne() {
        // Pas d'oracle d'existence : 404 dans les deux cas, jamais 403 (isolation user_id).
        when(repository.findByIdAndUserId(hostId, bob)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().requireOwned(bob, hostId))
                .isInstanceOf(RunnerHostNotFoundException.class);
    }

    @Test
    void listsOnlyTheHostsOfTheirOwner() {
        when(repository.findByUserIdOrderByCreatedAtDesc(alice)).thenReturn(List.of(new RunnerHost()));

        assertThat(service().list(alice)).hasSize(1);
        verify(repository).findByUserIdOrderByCreatedAtDesc(alice);
    }

    // ------------------------------------------------------- état de mission (F-60 / SF-60-01)

    @Test
    void aFreshHostIsAMissionInProgress() {
        // Le défaut est « en cours » et non « en attente » : un poste qu'on vient de créer EST une
        // mission en cours. Le mettre en attente inventerait un feu rouge que personne n'a posé.
        when(repository.save(any(RunnerHost.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service().create(alice, "Poste CAGIP").getMissionStatus())
                .isEqualTo(HostMissionStatus.ACTIVE);
    }

    @Test
    void declaresTheMissionStatusOfAnOwnedHost() {
        RunnerHost host = new RunnerHost();
        when(repository.findByIdAndUserId(hostId, alice)).thenReturn(Optional.of(host));

        assertThat(service().setMissionStatus(alice, hostId, HostMissionStatus.PENDING)
                .getMissionStatus()).isEqualTo(HostMissionStatus.PENDING);
        assertThat(host.getMissionStatus()).isEqualTo(HostMissionStatus.PENDING);
    }

    @Test
    void reapplyingTheSameMissionStatusChangesNothing() {
        // Idempotent : l'écran peut rejouer un ordre sans que cela devienne un incident.
        RunnerHost host = new RunnerHost();
        host.setMissionStatus(HostMissionStatus.CLOSED);
        when(repository.findByIdAndUserId(hostId, alice)).thenReturn(Optional.of(host));

        service().setMissionStatus(alice, hostId, HostMissionStatus.CLOSED);

        assertThat(host.getMissionStatus()).isEqualTo(HostMissionStatus.CLOSED);
    }

    @Test
    void refusesToDeclareTheMissionStatusOfSomeoneElsesHost() {
        // Isolation user_id : le poste de quelqu'un d'autre est introuvable, et rien n'est écrit.
        when(repository.findByIdAndUserId(hostId, bob)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().setMissionStatus(bob, hostId, HostMissionStatus.CLOSED))
                .isInstanceOf(RunnerHostNotFoundException.class);
    }

    @Test
    void closingAMissionCutsNothing() {
        // Clôturer RANGE un poste, elle ne l'éteint pas : ce service ne connaît ni les jetons, ni
        // les liaisons, ni les projets, ni le journal — et n'en touche donc aucun.
        RunnerHost host = new RunnerHost();
        host.setLastSeenAt(java.time.OffsetDateTime.now());
        host.setShell("posix");
        host.setRootName("dev");
        when(repository.findByIdAndUserId(hostId, alice)).thenReturn(Optional.of(host));

        service().setMissionStatus(alice, hostId, HostMissionStatus.CLOSED);

        assertThat(host.getLastSeenAt()).isNotNull();
        assertThat(host.getShell()).isEqualTo("posix");
        assertThat(host.getRootName()).isEqualTo("dev");
        verify(repository, never()).delete(any());
    }

    @Test
    void keepsAMissionInProgressWhenNothingIsDeclared() {
        // Aucune valeur nulle n'existe en base : un poste sans état serait un poste dont on ne
        // saurait pas dire s'il est en cours ou rangé.
        RunnerHost host = new RunnerHost();
        when(repository.findByIdAndUserId(hostId, alice)).thenReturn(Optional.of(host));

        service().setMissionStatus(alice, hostId, null);

        assertThat(host.getMissionStatus()).isEqualTo(HostMissionStatus.ACTIVE);
    }

    @Test
    void keepsOnlyTheLastSegmentOfTheRootTheRunnerDeclares() {
        // Le runner n'envoie qu'un nom, mais on ne fait pas confiance à un client pour ça : un
        // chemin absolu est réduit ici, et l'arborescence de la machine n'entre jamais en base.
        assertThat(RunnerHostService.lastSegment("/home/francky/dev")).isEqualTo("dev");
        assertThat(RunnerHostService.lastSegment("C:\\Users\\f\\projets")).isEqualTo("projets");
        assertThat(RunnerHostService.lastSegment("~/dev/")).isEqualTo("dev");
        assertThat(RunnerHostService.lastSegment("dev")).isEqualTo("dev");
        assertThat(RunnerHostService.lastSegment("   ")).isNull();
        assertThat(RunnerHostService.lastSegment(null)).isNull();
        assertThat(RunnerHostService.lastSegment("/" + "y".repeat(300))).hasSize(255);
    }

    @Test
    void recordsWhatTheRunnerDeclaresAboutItsMachineAtPairing() {
        RunnerHost host = new RunnerHost();
        when(repository.findById(hostId)).thenReturn(Optional.of(host));

        service().recordDeclaration(hostId, "/home/francky/dev", "linux", true);

        assertThat(host.getRootName()).isEqualTo("dev");
        assertThat(host.getOs()).isEqualTo("linux");
        assertThat(host.getElevated()).isTrue();
        assertThat(host.getLastSeenAt()).isNotNull();
    }

    @Test
    void recordsElevationEvenWhenNoRootWasDeclared() {
        // Un runner peut tourner en root sans avoir déclaré son dossier — et c'est justement le cas
        // où l'information compte le plus.
        RunnerHost host = new RunnerHost();
        when(repository.findById(hostId)).thenReturn(Optional.of(host));

        service().recordDeclaration(hostId, null, null, true);

        assertThat(host.getRootName()).isNull();
        assertThat(host.getElevated()).isTrue();
    }

    @Test
    void recordsTheDeclaredInterpreterOnTheMachine() {
        RunnerHost host = new RunnerHost();
        when(repository.findById(hostId)).thenReturn(Optional.of(host));

        service().recordRunnerShell(hostId, "POWERSHELL");

        assertThat(host.getShell()).isEqualTo("powershell");
    }

    @Test
    void ignoresAnInterpreterOutsideTheWhitelist() {
        // La valeur vient d'un client : hors des trois genres connus, elle n'entre pas en base.
        service().recordRunnerShell(hostId, "'; DROP TABLE runner_hosts; --");
        service().recordRunnerShell(hostId, null);

        verify(repository, never()).findById(any());
    }

    @Test
    void declaredShellIsNullForAProjectAttachedToNothing() {
        assertThat(service().declaredShell(null)).isNull();
        verify(repository, never()).findById(any());
    }

    // -------------------------------------------------- suppression (F-69 / SF-69-01)

    @Test
    void deletingAHostTakesItsCredentialsWithIt() {
        RunnerHost host = RunnerHost.builder().id(hostId).userId(alice).name("Poste CAGIP").build();
        when(repository.findByIdAndUserId(hostId, alice)).thenReturn(Optional.of(host));

        service().deleteWithCredentials(alice, hostId);

        // Un poste supprimé ne doit laisser derrière lui AUCUN moyen de s'authentifier : le jeton
        // authentifierait un runner au nom d'une machine qui n'existe plus, jusqu'à son expiration.
        verify(tokenRepository).deleteByHostId(hostId);
        verify(pairingCodeRepository).deleteByHostId(hostId);
        verify(seatLedgerService).forgetHost(hostId);
        verify(repository).delete(host);
    }

    @Test
    void theHostOfAnotherAccountIsNeverDeleted() {
        when(repository.findByIdAndUserId(hostId, bob)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().deleteWithCredentials(bob, hostId))
                .isInstanceOf(RunnerHostNotFoundException.class);

        verify(tokenRepository, never()).deleteByHostId(any());
        verify(pairingCodeRepository, never()).deleteByHostId(any());
        verify(repository, never()).delete(any(RunnerHost.class));
    }
}
