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

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private RunnerHostService service() {
        return new RunnerHostService(repository);
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
}
