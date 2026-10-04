package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;

/**
 * Relancer (F-175 / SF-175-06, décision D8) : « Demandé » depuis 3 jours ouvrés appelle une relance ;
 * le tableau, les compteurs et la liste jointe au tour le disent.
 */
class TerminalActionFollowUpTest {

    /** Lundi 5 octobre 2026, 10 h. */
    private static final OffsetDateTime MONDAY = OffsetDateTime.parse("2026-10-05T10:00:00Z");

    private final TerminalActionFollowUp followUp = new TerminalActionFollowUp(3);

    private TerminalAction asked(OffsetDateTime at) {
        return TerminalAction.builder().id(UUID.randomUUID()).userId(UUID.randomUUID())
                .workspaceId(UUID.randomUUID()).description("Relancer Zahi").kind(TerminalActionKind.ACTION)
                .status(TerminalActionStatus.DEMANDE).requestedAt(at).createdAt(at).updatedAt(at).build();
    }

    @Test
    @DisplayName("jours ouvrés : le week-end ne compte pas")
    void businessDays() {
        LocalDate thursday = LocalDate.of(2026, 10, 1);
        assertThat(TerminalActionFollowUp.businessDaysBetween(thursday, LocalDate.of(2026, 10, 5))).isEqualTo(2);
        assertThat(TerminalActionFollowUp.businessDaysBetween(thursday, LocalDate.of(2026, 10, 6))).isEqualTo(3);
        assertThat(TerminalActionFollowUp.businessDaysBetween(thursday, thursday)).isZero();
    }

    @Test
    @DisplayName("due à 3 jours ouvrés ; jamais pour « À faire » ; le seuil se règle")
    void dueAfterThreshold() {
        assertThat(followUp.isDue(asked(MONDAY.minusDays(4)), MONDAY)).isFalse(); // jeudi → lundi : 2
        assertThat(followUp.isDue(asked(MONDAY.minusDays(5)), MONDAY)).isTrue();  // mercredi → lundi : 3
        TerminalAction todo = asked(MONDAY.minusDays(10));
        todo.setStatus(TerminalActionStatus.A_FAIRE);
        assertThat(followUp.isDue(todo, MONDAY)).isFalse();
        assertThat(new TerminalActionFollowUp(1).isDue(asked(MONDAY.minusDays(3)), MONDAY)).isTrue();
        assertThat(new TerminalActionFollowUp(0).days()).isEqualTo(1);
    }

    @Test
    @DisplayName("le tableau marque « à relancer » et les compte ; les compteurs partout aussi")
    void boardAndSummary() {
        TerminalActionRepository repository = mock(TerminalActionRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        UUID userId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Workspace terminal = new Workspace();
        terminal.setId(UUID.randomUUID());
        terminal.setHostId(hostId);
        terminal.setName("agenor");
        when(workspaces.findByIdAndUserId(terminal.getId(), userId)).thenReturn(Optional.of(terminal));
        TerminalAction late = asked(MONDAY.minusDays(9));
        late.setWorkspaceId(terminal.getId());
        late.setHostId(hostId);
        TerminalAction fresh = asked(MONDAY.minusDays(1));
        fresh.setWorkspaceId(terminal.getId());
        fresh.setHostId(hostId);
        when(repository.findBoardOfHost(org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(hostId), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of(late, fresh));
        when(repository.findByUserIdAndStatusInOrderByCreatedAtAsc(userId, TerminalActionStatus.OPEN_STATES))
                .thenReturn(List.of(late, fresh));
        TerminalActionQueryService queries = new TerminalActionQueryService(repository, workspaces,
                Clock.fixed(MONDAY.toInstant(), ZoneOffset.UTC), followUp);

        TerminalActionBoardResponse board = queries.board(userId, terminal.getId());
        assertThat(board.aRelancer()).isEqualTo(1);
        assertThat(board.here()).filteredOn(TerminalActionResponse::followUpDue)
                .extracting(TerminalActionResponse::id).containsExactly(late.getId());

        TerminalActionSummaryResponse summary = queries.summary(userId);
        assertThat(summary.hosts()).singleElement().satisfies(count -> {
            assertThat(count.id()).isEqualTo(hostId);
            assertThat(count.demande()).isEqualTo(2);
            assertThat(count.aRelancer()).isEqualTo(1);
            assertThat(count.oldestOpenAt()).isEqualTo(late.getCreatedAt());
        });
        assertThat(summary.terminals()).singleElement()
                .satisfies(count -> assertThat(count.id()).isEqualTo(terminal.getId()));
    }

    @Test
    @DisplayName("la liste jointe au tour dit « RELANCE DUE »")
    void turnNoteSaysFollowUpDue() {
        TerminalAction late = asked(MONDAY.minusDays(9));
        assertThat(TerminalActionTurnNote.line(late, MONDAY, null, true)).contains("RELANCE DUE");
        assertThat(TerminalActionTurnNote.line(late, MONDAY, null)).doesNotContain("RELANCE DUE");
    }
}
