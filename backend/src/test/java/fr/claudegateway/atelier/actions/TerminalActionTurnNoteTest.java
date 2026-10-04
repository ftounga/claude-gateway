package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
 * La liste des attentes jointe au tour (F-175 / SF-175-02, décision D4) : ce terminal d'abord, puis
 * le reste du poste ; « demandé » dit à qui et depuis quand ; bornée ; vide sans attente.
 */
class TerminalActionTurnNoteTest {

    private final TerminalActionRepository repository = mock(TerminalActionRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC);
    private final OffsetDateTime now = OffsetDateTime.now(clock);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID here = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private Workspace terminal;
    private TerminalActionTurnNote note;

    @BeforeEach
    void setUp() {
        terminal = new Workspace();
        terminal.setId(here);
        terminal.setHostId(hostId);
        Workspace tfstate = new Workspace();
        tfstate.setName("gitlab-tfstate");
        when(workspaces.findByIdAndUserId(other, userId)).thenReturn(Optional.of(tfstate));
        note = new TerminalActionTurnNote(repository, workspaces, clock);
    }

    private TerminalAction action(UUID workspaceId, String description, TerminalActionStatus status,
                                  int ageDays) {
        return TerminalAction.builder().id(UUID.randomUUID()).userId(userId).workspaceId(workspaceId)
                .hostId(hostId).description(description).kind(TerminalActionKind.ACTION).status(status)
                .createdAt(now.minusDays(ageDays)).updatedAt(now).build();
    }

    @Test
    @DisplayName("aucune attente ouverte : rien — la consigne reste à l'octet près")
    void emptyWhenNothingIsOpen() {
        when(repository.findByUserIdAndHostIdAndStatusIn(userId, hostId, TerminalActionStatus.OPEN_STATES))
                .thenReturn(List.of());
        assertThat(note.noteFor(userId, terminal)).isEmpty();
    }

    @Test
    @DisplayName("ce terminal d'abord, puis le poste ; « demandé » dit à qui, quand, par où")
    void hereFirstThenTheHost() {
        TerminalAction asked = action(here, "Demander le compte forge CAPFM", TerminalActionStatus.DEMANDE, 5);
        asked.setDedupKey("compte-forge-capfm");
        asked.setRequestedAt(now.minusDays(4));
        asked.setRequestedTo("Zahi");
        asked.setChannel("Teams");
        TerminalAction elsewhere = action(other, "Obtenir la dérogation SCP", TerminalActionStatus.A_FAIRE, 9);
        when(repository.findByUserIdAndHostIdAndStatusIn(userId, hostId, TerminalActionStatus.OPEN_STATES))
                .thenReturn(List.of(elsewhere, asked));

        String text = note.noteFor(userId, terminal);

        assertThat(text).startsWith(TerminalActionTurnNote.HEADER).endsWith(TerminalActionTurnNote.FOOTER);
        assertThat(text.indexOf("Ce terminal")).isLessThan(text.indexOf("Ailleurs sur le poste"));
        assertThat(text).contains("DEMANDÉ le 30/09 à Zahi par Teams · attend depuis 4 j")
                .contains("key=compte-forge-capfm")
                .contains("née dans « gitlab-tfstate »")
                .contains("À FAIRE · ouverte depuis 9 j")
                .contains("id=" + elsewhere.getId());
    }

    @Test
    @DisplayName("bornée : au plus 30 lignes et 3 000 caractères, le reste est compté")
    void bounded() {
        List<TerminalAction> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(action(here, "Attente numéro " + i + " " + "x".repeat(60), TerminalActionStatus.A_FAIRE, i));
        }
        when(repository.findByUserIdAndHostIdAndStatusIn(userId, hostId, TerminalActionStatus.OPEN_STATES))
                .thenReturn(many);

        String text = note.noteFor(userId, terminal);

        assertThat(text.length()).isLessThanOrEqualTo(TerminalActionTurnNote.MAX_CHARS);
        assertThat(text.lines().filter(l -> l.startsWith("- [")).count())
                .isLessThanOrEqualTo(TerminalActionTurnNote.MAX_LINES);
        assertThat(text).contains("autre(s), plus ancienne(s), non montrée(s)");
        assertThat(text).contains("Attente numéro 0 "); // la plus récente passe d'abord
    }

    @Test
    @DisplayName("terminal hébergé (sans poste) : sa seule liste, jamais une lecture par poste")
    void hostedTerminalReadsItsOwnList() {
        terminal.setHostId(null);
        when(repository.findByUserIdAndWorkspaceIdAndStatusInOrderByCreatedAtAsc(userId, here,
                TerminalActionStatus.OPEN_STATES))
                .thenReturn(List.of(action(here, "Vérifier le droit", TerminalActionStatus.A_FAIRE, 0)));

        assertThat(note.noteFor(userId, terminal)).contains("Vérifier le droit").contains("aujourd'hui");
        verify(repository, never()).findByUserIdAndHostIdAndStatusIn(any(), any(), any());
    }

    @Test
    @DisplayName("une fermeture proposée est signalée dans la ligne")
    void proposalIsShown() {
        TerminalAction proposed = action(here, "Relancer Karim", TerminalActionStatus.A_FAIRE, 1);
        proposed.setProposedStatus(TerminalActionStatus.FAIT);
        when(repository.findByUserIdAndHostIdAndStatusIn(userId, hostId, TerminalActionStatus.OPEN_STATES))
                .thenReturn(List.of(proposed));
        assertThat(note.noteFor(userId, terminal)).contains("fermeture proposée, en attente de confirmation");
    }
}
