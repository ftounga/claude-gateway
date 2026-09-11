package fr.claudegateway.runner.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.RunnerStatusService;
import fr.claudegateway.runner.RunnerStatusService.RunnerStatus;
import fr.claudegateway.runner.audit.RunnerAudit;
import fr.claudegateway.runner.audit.RunnerAuditActivity;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.runner.host.dto.RunnerHostOverviewResponse;
import fr.claudegateway.runner.host.dto.RunnerHostOverviewResponse.HostProjectSummary;

/**
 * La vue d'ensemble des postes (F-49 / SF-49-01) : ce que l'écran reçoit en une seule lecture.
 *
 * <p>Ce qui est vérifié ici tient en trois questions, celles que l'écran pose : <b>où en est la
 * machine</b>, <b>qu'est-ce qui vit dessous</b>, et <b>qu'est-ce qui tourne</b>.</p>
 */
@ExtendWith(MockitoExtension.class)
class RunnerHostOverviewServiceTest {

    @Mock private RunnerHostService hostService;
    @Mock private WorkspaceService workspaceService;
    @Mock private RunnerStatusService statusService;
    @Mock private RunnerAuditRepository auditRepository;
    @Mock private fr.claudegateway.terminals.LiveTerminalService liveTerminals;

    private final UUID alice = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private RunnerHostOverviewService service() {
        return service(Duration.ofHours(1), Duration.ofMinutes(2));
    }

    private RunnerHostOverviewService service(Duration observed, Duration activeWithin) {
        return new RunnerHostOverviewService(hostService, workspaceService, statusService,
                auditRepository, liveTerminals, observed, activeWithin);
    }

    // ------------------------------------------------------------------ décors

    private RunnerHost host(String name, OffsetDateTime lastSeenAt, OffsetDateTime createdAt) {
        return RunnerHost.builder().id(hostId).userId(alice).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false).lastSeenAt(lastSeenAt)
                .createdAt(createdAt).build();
    }

    private Workspace project(UUID id, String name, String path) {
        return Workspace.builder().id(id).userId(alice).name(name).hostId(hostId).projectPath(path)
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build();
    }

    private RunnerAuditActivity activity(UUID workspaceId, OffsetDateTime lastAt, long calls) {
        return new RunnerAuditActivity() {
            @Override public UUID getWorkspaceId() {
                return workspaceId;
            }

            @Override public OffsetDateTime getLastAt() {
                return lastAt;
            }

            @Override public long getCalls() {
                return calls;
            }
        };
    }

    private void connected(RunnerHost host, boolean connected) {
        when(statusService.statusOf(alice, host)).thenReturn(new RunnerStatus(connected,
                host.getLastSeenAt(), "posix", host.getId(), host.getName(), "dev", false));
    }

    private void toolIs(UUID workspaceId, String tool) {
        lenient().when(auditRepository
                        .findFirstByUserIdAndWorkspaceIdOrderByCreatedAtDesc(alice, workspaceId))
                .thenReturn(Optional.of(RunnerAudit.builder().tool(tool).build()));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void describesTheMachineItsProjectsAndWhatIsRunning() {
        OffsetDateTime now = OffsetDateTime.now();
        RunnerHost machine = host("Poste CAGIP", now.minusSeconds(10), now.minusDays(3));
        UUID actif = UUID.randomUUID();
        UUID calme = UUID.randomUUID();
        when(hostService.list(alice)).thenReturn(List.of(machine));
        connected(machine, true);
        when(workspaceService.listByHost(alice, hostId))
                .thenReturn(List.of(project(calme, "api", "api"), project(actif, "web", "web")));
        when(auditRepository.aggregateActivityByHost(eq(alice), eq(hostId), any()))
                .thenReturn(List.of(
                        activity(actif, now.minusSeconds(20), 12L),
                        activity(calme, now.minusMinutes(30), 3L)));
        toolIs(actif, "bash");
        toolIs(calme, "read");

        List<RunnerHostOverviewResponse> overview = service().overview(alice);

        assertThat(overview).hasSize(1);
        RunnerHostOverviewResponse poste = overview.getFirst();
        assertThat(poste.name()).isEqualTo("Poste CAGIP");
        assertThat(poste.connected()).isTrue();
        assertThat(poste.rootName()).isEqualTo("dev");
        assertThat(poste.os()).isEqualTo("linux");
        assertThat(poste.shell()).isEqualTo("posix");
        assertThat(poste.elevated()).isFalse();
        // Le plus actif d'abord : c'est ce que l'œil cherche en premier sur une vue d'état.
        assertThat(poste.projects()).extracting(HostProjectSummary::name)
                .containsExactly("web", "api");
        assertThat(poste.activeProjects()).isEqualTo(1);
        assertThat(poste.lastActivityAt()).isEqualTo(now.minusSeconds(20));

        HostProjectSummary web = poste.projects().getFirst();
        assertThat(web.active()).isTrue();
        assertThat(web.calls()).isEqualTo(12L);
        assertThat(web.lastTool()).isEqualTo("bash");
        assertThat(web.projectPath()).isEqualTo("web");
        assertThat(web.executionTarget()).isEqualTo("RUNNER");

        HostProjectSummary api = poste.projects().get(1);
        assertThat(api.active()).isFalse();
        assertThat(api.lastActivityAt()).isEqualTo(now.minusMinutes(30));
        assertThat(api.calls()).isEqualTo(3L);
    }

    @Test
    void aHostWithoutProjectIsNotAnError() {
        // C'est l'état d'un poste qu'on vient de créer : l'écran doit pouvoir le dire.
        RunnerHost machine = host("Neuf", null, OffsetDateTime.now());
        when(hostService.list(alice)).thenReturn(List.of(machine));
        connected(machine, false);
        when(workspaceService.listByHost(alice, hostId)).thenReturn(List.of());
        when(auditRepository.aggregateActivityByHost(eq(alice), eq(hostId), any()))
                .thenReturn(List.of());

        RunnerHostOverviewResponse poste = service().overview(alice).getFirst();

        assertThat(poste.projects()).isEmpty();
        assertThat(poste.activeProjects()).isZero();
        assertThat(poste.lastActivityAt()).isNull();
        assertThat(poste.connected()).isFalse();
    }

    @Test
    void aSilentProjectCostsNoExtraQuery() {
        // Le dernier outil ne se demande que pour un projet qui a bougé : un poste de vingt projets
        // muets ne doit pas produire vingt lectures de journal.
        RunnerHost machine = host("Poste", OffsetDateTime.now(), OffsetDateTime.now());
        UUID muet = UUID.randomUUID();
        when(hostService.list(alice)).thenReturn(List.of(machine));
        connected(machine, true);
        when(workspaceService.listByHost(alice, hostId)).thenReturn(List.of(project(muet, "api", null)));
        when(auditRepository.aggregateActivityByHost(eq(alice), eq(hostId), any()))
                .thenReturn(List.of());

        HostProjectSummary api = service().overview(alice).getFirst().projects().getFirst();

        assertThat(api.lastTool()).isNull();
        assertThat(api.calls()).isZero();
        assertThat(api.lastActivityAt()).isNull();
        assertThat(api.active()).isFalse();
        org.mockito.Mockito.verify(auditRepository, org.mockito.Mockito.never())
                .findFirstByUserIdAndWorkspaceIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void aClosedMissionStaysInTheView() {
        // « Se ranger sans disparaître » (F-60) : la gateway rend l'état, elle ne filtre pas. Le
        // rangement est une affaire d'écran — sinon rendre les missions closes consultables
        // demanderait un second appel, donc deux états de vue à synchroniser.
        RunnerHost machine = host("Poste CAGIP", OffsetDateTime.now(), OffsetDateTime.now());
        machine.setMissionStatus(HostMissionStatus.CLOSED);
        when(hostService.list(alice)).thenReturn(List.of(machine));
        connected(machine, false);
        when(workspaceService.listByHost(alice, hostId)).thenReturn(List.of());
        when(auditRepository.aggregateActivityByHost(eq(alice), eq(hostId), any()))
                .thenReturn(List.of());

        assertThat(service().overview(alice))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.missionStatus()).isEqualTo(HostMissionStatus.CLOSED);
                    // L'état de mission est DÉCLARÉ : il ne se déduit pas de la présence du runner.
                    assertThat(view.connected()).isFalse();
                });
    }

    @Test
    void anUnknownShellComesOutNullRatherThanRelayed() {
        // La colonne est alimentée par une trame venue d'un client : elle repasse par la liste
        // blanche avant de sortir de la gateway.
        RunnerHost machine = host("Poste", OffsetDateTime.now(), OffsetDateTime.now());
        machine.setShell("fish");
        when(hostService.list(alice)).thenReturn(List.of(machine));
        connected(machine, true);
        when(workspaceService.listByHost(alice, hostId)).thenReturn(List.of());
        when(auditRepository.aggregateActivityByHost(eq(alice), eq(hostId), any()))
                .thenReturn(List.of());

        assertThat(service().overview(alice).getFirst().shell()).isNull();
    }

    @Test
    void ordersTheLastSeenFirstAndTheNeverSeenLast() {
        OffsetDateTime now = OffsetDateTime.now();
        RunnerHost vieux = RunnerHost.builder().id(UUID.randomUUID()).userId(alice).name("vieux")
                .lastSeenAt(now.minusDays(2)).createdAt(now.minusDays(9)).build();
        RunnerHost recent = RunnerHost.builder().id(UUID.randomUUID()).userId(alice).name("recent")
                .lastSeenAt(now.minusMinutes(1)).createdAt(now.minusDays(1)).build();
        RunnerHost jamais = RunnerHost.builder().id(UUID.randomUUID()).userId(alice).name("jamais")
                .createdAt(now).build();
        when(hostService.list(alice)).thenReturn(List.of(vieux, jamais, recent));
        for (RunnerHost host : List.of(vieux, recent, jamais)) {
            connected(host, false);
            lenient().when(workspaceService.listByHost(alice, host.getId())).thenReturn(List.of());
            lenient().when(auditRepository.aggregateActivityByHost(eq(alice), eq(host.getId()), any()))
                    .thenReturn(List.of());
        }

        assertThat(service().overview(alice)).extracting(RunnerHostOverviewResponse::name)
                .containsExactly("recent", "vieux", "jamais");
    }

    @Test
    void anActivityWindowWiderThanTheObservedOneIsBroughtBack() {
        // Un projet ne peut pas être « actif » sur une fenêtre qu'on n'observe pas.
        OffsetDateTime now = OffsetDateTime.now();
        RunnerHost machine = host("Poste", now, now.minusDays(1));
        UUID projet = UUID.randomUUID();
        when(hostService.list(alice)).thenReturn(List.of(machine));
        connected(machine, true);
        when(workspaceService.listByHost(alice, hostId))
                .thenReturn(List.of(project(projet, "api", "api")));
        when(auditRepository.aggregateActivityByHost(eq(alice), eq(hostId), any()))
                .thenReturn(List.of(activity(projet, now.minusMinutes(20), 2L)));
        toolIs(projet, "bash");

        // Fenêtre observée de 5 min, fenêtre d'activité demandée à 2 h → ramenée à 5 min.
        HostProjectSummary api = service(Duration.ofMinutes(5), Duration.ofHours(2))
                .overview(alice).getFirst().projects().getFirst();

        assertThat(api.active()).isFalse();
    }

    @Test
    void anAbsurdObservedWindowIsCapped() {
        // Une vue d'état ne fait pas d'archéologie : la fenêtre est bornée à 24 h.
        RunnerHostOverviewService bornee =
                service(Duration.ofDays(400), Duration.ofMinutes(2));
        RunnerHost machine = host("Poste", OffsetDateTime.now(), OffsetDateTime.now());
        when(hostService.list(alice)).thenReturn(List.of(machine));
        connected(machine, true);
        when(workspaceService.listByHost(alice, hostId)).thenReturn(List.of());
        org.mockito.ArgumentCaptor<OffsetDateTime> since =
                org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        when(auditRepository.aggregateActivityByHost(eq(alice), eq(hostId), since.capture()))
                .thenReturn(List.of());

        bornee.overview(alice);

        assertThat(since.getValue())
                .isAfter(OffsetDateTime.now().minus(RunnerHostOverviewService.MAX_OBSERVED_WINDOW)
                        .minusMinutes(1));
    }
}
