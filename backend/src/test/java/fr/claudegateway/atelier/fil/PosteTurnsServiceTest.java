package fr.claudegateway.atelier.fil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.live.RemoteTurnSource;
import fr.claudegateway.atelier.live.TurnDigest;
import fr.claudegateway.atelier.live.TurnSummary;

/** Les tours du poste (F-191 / SF-191-01) : portée poste, isolation, dédoublonnage, ordre. */
class PosteTurnsServiceTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final LiveTurnRegistry registry = mock(LiveTurnRegistry.class);
    private final RemoteTurnSource remote = mock(RemoteTurnSource.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC);

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID poste = UUID.randomUUID();
    private final UUID autrePoste = UUID.randomUUID();
    private final Workspace ici = ws(UUID.randomUUID(), "ici", alice, poste, false);
    private final Workspace racine = ws(UUID.randomUUID(), "racine", alice, poste, true);
    private final Workspace voisin = ws(UUID.randomUUID(), "voisin", alice, poste, false);
    private final Workspace ailleurs = ws(UUID.randomUUID(), "ailleurs", alice, autrePoste, false);

    private PosteTurnsService service;

    @BeforeEach
    void setUp() {
        service = new PosteTurnsService(workspaceService, workspaces, registry, remote, clock);
        when(workspaceService.requireOwned(alice, ici.getId())).thenReturn(ici);
        when(workspaces.findByUserIdAndHostId(alice, poste)).thenReturn(List.of(ici, racine, voisin));
        when(remote.remoteSummariesOf(alice)).thenReturn(List.of());
    }

    @Test
    @DisplayName("seuls les tours des terminaux du poste ; autre poste et autrui écartés")
    void scopedToPoste() {
        when(registry.summariesOf(alice)).thenReturn(List.of(
                summary(alice, voisin, "running", 100, null),
                summary(alice, ailleurs, "running", 50, null),
                summary(bob, ici, "running", 10, null)));

        PosteTurnsResponse r = service.turnsOfPoste(alice, ici.getId());

        assertThat(r.hostId()).isEqualTo(poste);
        assertThat(r.serverTime()).isEqualTo(clock.millis());
        assertThat(r.turns()).singleElement().satisfies(item -> {
            assertThat(item.workspaceName()).isEqualTo("voisin");
            assertThat(item.here()).isFalse();
            assertThat(item.root()).isFalse();
        });
    }

    @Test
    @DisplayName("ordre : en attente, puis en cours du plus ancien, puis finis du plus récent")
    void ordering() {
        Workspace s4 = ws(UUID.randomUUID(), "s4", alice, poste, false);
        when(workspaces.findByUserIdAndHostId(alice, poste)).thenReturn(List.of(ici, racine, voisin, s4));
        when(registry.summariesOf(alice)).thenReturn(List.of(
                summary(alice, ici, "running", 300, null),
                summary(alice, racine, "running", 100, null),
                summary(alice, s4, "done", 50, 900L)));
        when(remote.remoteSummariesOf(alice)).thenReturn(List.of(
                summary(alice, voisin, "waiting", 500, null)));

        List<PosteTurnsResponse.Item> turns = service.turnsOfPoste(alice, ici.getId()).turns();

        assertThat(turns).extracting(PosteTurnsResponse.Item::workspaceName)
                .containsExactly("voisin", "racine", "ici", "s4");
        assertThat(turns.get(1).root()).isTrue();
        assertThat(turns.get(2).here()).isTrue();
    }

    @Test
    @DisplayName("un terminal qui tourne n'affiche pas son tour fini ; un seul fini par terminal ; doublons fusionnés")
    void dedup() {
        TurnSummary live = summary(alice, ici, "running", 500, null);
        when(registry.summariesOf(alice)).thenReturn(List.of(
                live,
                summary(alice, ici, "done", 100, 400L),
                summary(alice, voisin, "done", 100, 200L),
                summary(alice, voisin, "failed", 250, 300L)));
        when(remote.remoteSummariesOf(alice)).thenReturn(List.of(live));

        List<PosteTurnsResponse.Item> turns = service.turnsOfPoste(alice, ici.getId()).turns();

        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).state()).isEqualTo("running");
        assertThat(turns.get(1).workspaceName()).isEqualTo("voisin");
        assertThat(turns.get(1).state()).isEqualTo("failed");
    }

    @Test
    @DisplayName("terminal sans poste : la portée est l'ensemble des terminaux sans poste")
    void noPoste() {
        Workspace sandbox = ws(UUID.randomUUID(), "bac", alice, null, false);
        Workspace autre = ws(UUID.randomUUID(), "autre", alice, null, false);
        when(workspaceService.requireOwned(alice, sandbox.getId())).thenReturn(sandbox);
        when(workspaces.findByUserIdAndHostIdIsNull(alice)).thenReturn(List.of(sandbox, autre));
        when(registry.summariesOf(alice)).thenReturn(List.of(
                summary(alice, autre, "running", 1, null),
                summary(alice, voisin, "running", 2, null)));

        PosteTurnsResponse r = service.turnsOfPoste(alice, sandbox.getId());

        assertThat(r.hostId()).isNull();
        assertThat(r.turns()).extracting(PosteTurnsResponse.Item::workspaceName).containsExactly("autre");
    }

    @Test
    @DisplayName("terminal d'autrui : introuvable")
    void notOwned() {
        when(workspaceService.requireOwned(bob, ici.getId())).thenThrow(new WorkspaceNotFoundException("x"));

        assertThatThrownBy(() -> service.turnsOfPoste(bob, ici.getId()))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    @DisplayName("au plus vingt lignes")
    void bounded() {
        List<Workspace> many = new java.util.ArrayList<>(List.of(ici));
        List<TurnSummary> sums = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Workspace w = ws(UUID.randomUUID(), "s" + i, alice, poste, false);
            many.add(w);
            sums.add(summary(alice, w, "running", i, null));
        }
        when(workspaces.findByUserIdAndHostId(alice, poste)).thenReturn(many);
        when(registry.summariesOf(alice)).thenReturn(sums);

        assertThat(service.turnsOfPoste(alice, ici.getId()).turns()).hasSize(PosteTurnsService.MAX_TURNS);
    }

    private static TurnSummary summary(UUID user, Workspace w, String state, long startedAt, Long endedAt) {
        return new TurnSummary(UUID.randomUUID(), user, w.getId(), state,
                "waiting".equals(state) ? "approval" : null, "demande", startedAt, endedAt, 1, 3, "étape", "bash",
                List.of(new TurnDigest.SubAgent("task", "lire", "running", startedAt)));
    }

    private static Workspace ws(UUID id, String name, UUID owner, UUID host, boolean root) {
        Workspace w = new Workspace();
        w.setId(id);
        w.setName(name);
        w.setUserId(owner);
        w.setHostId(host);
        w.setHostTerminal(root);
        return w;
    }
}
