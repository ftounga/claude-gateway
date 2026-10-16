package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;

/**
 * La vue par interlocuteur (F-180 / SF-180-05) : regroupement, tri par ancienneté, gestes à soi exclus,
 * sujets bloqués, et la mesure des délais demande → réponse par personne et par nature.
 */
class InterlocuteurBoardServiceTest {

    private final TerminalActionRepository actions = mock(TerminalActionRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final InterlocuteurRepository interlocuteurs = mock(InterlocuteurRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID otherTerminal = UUID.randomUUID();
    private final List<TerminalAction> all = new ArrayList<>();
    private final Interlocuteur remi = Interlocuteur.builder().id(UUID.randomUUID()).userId(userId).hostId(hostId)
            .displayName("Rémi Dupont").normalizedName("remi dupont").role("Réseau").build();
    private InterlocuteurBoardService service;

    @BeforeEach
    void setUp() {
        Workspace terminal = new Workspace();
        terminal.setId(workspaceId);
        terminal.setHostId(hostId);
        terminal.setName("data-platform");
        Workspace other = new Workspace();
        other.setId(otherTerminal);
        other.setHostId(hostId);
        other.setName("connecteur");
        when(workspaces.findByIdAndUserId(workspaceId, userId)).thenReturn(Optional.of(terminal));
        when(workspaces.findByIdAndUserId(otherTerminal, userId)).thenReturn(Optional.of(other));
        when(actions.findByUserIdAndHostId(userId, hostId)).thenReturn(all);
        when(interlocuteurs.findByUserIdAndHostIdOrderByDisplayNameAsc(userId, hostId)).thenReturn(List.of(remi));
        service = new InterlocuteurBoardService(actions, workspaces, interlocuteurs, clock, new TerminalActionFollowUp(3));
    }

    private TerminalAction add(String description, UUID interlocuteur, String person, int ageDays,
                               TerminalActionStatus status, UUID terminal) {
        OffsetDateTime created = OffsetDateTime.now(clock).minusDays(ageDays);
        TerminalAction a = TerminalAction.builder().id(UUID.randomUUID()).userId(userId).hostId(hostId)
                .workspaceId(terminal).description(description).interlocuteurId(interlocuteur).person(person)
                .status(status).kind(TerminalActionKind.ACTION).createdAt(created).updatedAt(created).build();
        all.add(a);
        return a;
    }

    @Test
    @DisplayName("Rémi et ses 11 attentes, triées par ancienneté, avec les sujets qu'il bloque")
    void groupsAndSorts() {
        for (int i = 0; i < 11; i++) {
            add("Attente " + i, remi.getId(), "Rémi", 2 + i, TerminalActionStatus.A_FAIRE,
                    i % 2 == 0 ? workspaceId : otherTerminal);
        }
        add("Demander à Karim", null, "Karim", 1, TerminalActionStatus.A_FAIRE, workspaceId);

        var board = service.board(userId, workspaceId);

        assertThat(board.people()).hasSize(2);
        var first = board.people().get(0);
        assertThat(first.name()).isEqualTo("Rémi Dupont");
        assertThat(first.openCount()).isEqualTo(11);
        assertThat(first.open().get(0).description()).isEqualTo("Attente 10"); // la plus ancienne d'abord
        assertThat(first.subjects()).containsExactlyInAnyOrder("data-platform", "connecteur");
        assertThat(board.people().get(1).name()).isEqualTo("Karim");
        assertThat(board.people().get(1).interlocuteurId()).isNull();
    }

    @Test
    @DisplayName("un geste à toi n'est pas une personne à relancer")
    void excludesMoi() {
        TerminalAction sso = add("Se reconnecter en SSO", null, "moi", 1, TerminalActionStatus.A_FAIRE, workspaceId);
        sso.setQui(TerminalActionQui.MOI);
        assertThat(service.board(userId, workspaceId).people()).isEmpty();
    }

    @Test
    @DisplayName("mesure : délai demande → réponse par personne, par nature ; incohérent ignoré")
    void measures() {
        add("Ouverte", remi.getId(), "Rémi", 1, TerminalActionStatus.DEMANDE, workspaceId);
        TerminalAction a = add("Accès VPN", remi.getId(), "Rémi", 10, TerminalActionStatus.FAIT, workspaceId);
        a.setCategorie(TerminalActionCategorie.ACCES);
        a.setRequestedAt(OffsetDateTime.now(clock).minusDays(9));
        a.setClosedAt(OffsetDateTime.now(clock).minusDays(3)); // 6 j
        TerminalAction b = add("Validation", remi.getId(), "Rémi", 5, TerminalActionStatus.FAIT, workspaceId);
        b.setCategorie(TerminalActionCategorie.VALIDATION);
        b.setRequestedAt(OffsetDateTime.now(clock).minusDays(4));
        b.setClosedAt(OffsetDateTime.now(clock).minusDays(2)); // 2 j
        TerminalAction broken = add("Incohérente", remi.getId(), "Rémi", 5, TerminalActionStatus.FAIT, workspaceId);
        broken.setRequestedAt(OffsetDateTime.now(clock).minusDays(1));
        broken.setClosedAt(OffsetDateTime.now(clock).minusDays(2)); // fermée avant d'être demandée

        var person = service.board(userId, workspaceId).people().get(0);

        assertThat(person.avgResponseDays()).isEqualTo(4.0);
        assertThat(person.responseCount()).isEqualTo(2);
        assertThat(person.avgByCategorie()).containsEntry(TerminalActionCategorie.ACCES, 6.0)
                .containsEntry(TerminalActionCategorie.VALIDATION, 2.0);
        assertThat(person.demandeCount()).isEqualTo(1);
        var acces = service.board(userId, workspaceId).byCategorie().stream()
                .filter(c -> c.categorie() == TerminalActionCategorie.ACCES).findFirst().orElseThrow();
        assertThat(acces.avgResponseDays()).isEqualTo(6.0);
    }

    @Test
    @DisplayName("hors fenêtre de 180 j : pas mesuré")
    void measureWindow() {
        TerminalAction old = TerminalAction.builder().status(TerminalActionStatus.FAIT)
                .requestedAt(OffsetDateTime.now(clock).minusDays(300)).closedAt(OffsetDateTime.now(clock).minusDays(200)).build();
        assertThat(InterlocuteurBoardService.delayDays(old, OffsetDateTime.now(clock).minusDays(180))).isNull();
    }

    @Test
    @DisplayName("terminal d'un autre compte : introuvable")
    void isolation() {
        assertThatThrownBy(() -> service.board(UUID.randomUUID(), workspaceId))
                .isInstanceOf(TerminalActionNotFoundException.class);
    }
}
