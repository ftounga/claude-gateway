package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;

/**
 * Échéances et point du matin (F-180 / SF-180-06) : l'état d'une échéance, ce que le point du matin
 * rassemble, la ligne du tour, et l'échéance dite par l'agent.
 */
class TerminalActionMorningTest {

    private final TerminalActionRepository actions = mock(TerminalActionRepository.class);
    private final AccesObtenuRepository acces = mock(AccesObtenuRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    /** Vendredi 16/10/2026, 9 h à Paris. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T07:00:00Z"), ZoneOffset.UTC);
    private final LocalDate today = LocalDate.of(2026, 10, 16);
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final List<TerminalAction> open = new ArrayList<>();
    private Workspace terminal;

    @BeforeEach
    void setUp() {
        terminal = new Workspace();
        terminal.setId(workspaceId);
        terminal.setHostId(hostId);
        terminal.setName("data-platform");
        when(workspaces.findByIdAndUserId(workspaceId, userId)).thenReturn(Optional.of(terminal));
        when(actions.findByUserIdAndHostIdAndStatusIn(eq(userId), eq(hostId), any())).thenReturn(open);
    }

    private TerminalAction add(String description, LocalDate due, TerminalActionStatus status, OffsetDateTime requestedAt) {
        OffsetDateTime created = OffsetDateTime.now(clock).minusDays(10);
        TerminalAction a = TerminalAction.builder().id(UUID.randomUUID()).userId(userId).hostId(hostId)
                .workspaceId(workspaceId).description(description).status(status).kind(TerminalActionKind.ACTION)
                .dueAt(due).requestedAt(requestedAt).createdAt(created).updatedAt(created).build();
        open.add(a);
        return a;
    }

    @Test
    @DisplayName("état d'échéance : aucune, plus tard, aujourd'hui, dépassée ; fermée = aucune")
    void dueState() {
        TerminalAction a = TerminalAction.builder().status(TerminalActionStatus.A_FAIRE).build();
        assertThat(TerminalActionResponse.dueStateOf(a, today)).isEqualTo("NONE");
        a.setDueAt(today.plusDays(2));
        assertThat(TerminalActionResponse.dueStateOf(a, today)).isEqualTo("LATER");
        a.setDueAt(today);
        assertThat(TerminalActionResponse.dueStateOf(a, today)).isEqualTo("TODAY");
        a.setDueAt(today.minusDays(1));
        assertThat(TerminalActionResponse.dueStateOf(a, today)).isEqualTo("OVERDUE");
        a.setStatus(TerminalActionStatus.FAIT);
        assertThat(TerminalActionResponse.dueStateOf(a, today)).isEqualTo("NONE");
    }

    @Test
    @DisplayName("point du matin : relances dues, échéances du jour et dépassées, accès qui expirent (±7 j)")
    void morningPoint() {
        add("Relance due", null, TerminalActionStatus.DEMANDE, OffsetDateTime.now(clock).minusDays(8));
        add("Aujourd'hui", today, TerminalActionStatus.A_FAIRE, null);
        add("Hier", today.minusDays(1), TerminalActionStatus.A_FAIRE, null);
        add("Plus tard", today.plusDays(5), TerminalActionStatus.A_FAIRE, null);
        AccesObtenu soon = AccesObtenu.builder().id(UUID.randomUUID()).system("VPN").obtainedAt(today.minusMonths(6))
                .expiresAt(today.plusDays(4)).build();
        AccesObtenu far = AccesObtenu.builder().id(UUID.randomUUID()).system("Forge").obtainedAt(today.minusMonths(6))
                .expiresAt(today.plusDays(40)).build();
        AccesObtenu gone = AccesObtenu.builder().id(UUID.randomUUID()).system("Jira").obtainedAt(today.minusMonths(6))
                .expiresAt(today.minusDays(2)).build();
        when(acces.findByUserIdAndHostIdOrderByObtainedAtDesc(userId, hostId)).thenReturn(List.of(soon, far, gone));
        var service = new TerminalActionMorningService(actions, acces, workspaces, new TerminalActionFollowUp(3), clock);

        var point = service.morning(userId, workspaceId);

        assertThat(point.followUps()).extracting(TerminalActionResponse::description).containsExactly("Relance due");
        assertThat(point.dueToday()).extracting(TerminalActionResponse::description).containsExactly("Aujourd'hui");
        assertThat(point.overdue()).extracting(TerminalActionResponse::description).containsExactly("Hier");
        assertThat(point.accesExpiring()).extracting(TerminalActionMorningService.ExpiringAcces::system)
                .containsExactly("VPN", "Jira");
        assertThat(point.accesExpiring().get(1).expired()).isTrue();
    }

    @Test
    @DisplayName("terminal d'un autre compte : introuvable")
    void isolation() {
        var service = new TerminalActionMorningService(actions, acces, workspaces, new TerminalActionFollowUp(3), clock);
        assertThatThrownBy(() -> service.morning(UUID.randomUUID(), workspaceId))
                .isInstanceOf(TerminalActionNotFoundException.class);
    }

    @Test
    @DisplayName("ligne du tour : « échéance 20/10 », « ÉCHÉANCE DÉPASSÉE »")
    void turnLine() {
        TerminalAction a = add("Valider", today.plusDays(4), TerminalActionStatus.A_FAIRE, null);
        assertThat(TerminalActionTurnNote.line(a, OffsetDateTime.now(clock), null)).contains("· échéance 20/10");
        a.setDueAt(today.minusDays(1));
        assertThat(TerminalActionTurnNote.line(a, OffsetDateTime.now(clock), null)).contains("ÉCHÉANCE DÉPASSÉE (15/10)");
    }

    @Test
    @DisplayName("l'agent dit l'échéance ; une date illisible est une erreur d'outil")
    void agentDueDate() throws Exception {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(terminal);
        when(actions.findByUserIdAndWorkspaceIdAndDedupKey(any(), any(), anyString())).thenReturn(Optional.empty());
        when(actions.save(any())).thenAnswer(i -> i.getArgument(0));
        var executor = new TerminalActionToolExecutor(new TerminalActionService(actions, workspaceService, clock));
        ObjectMapper json = new ObjectMapper();

        var ok = executor.execute(userId, terminal, json.readTree(
                "{\"description\":\"Valider le budget\",\"due_date\":\"2026-10-23\"}"));
        assertThat(ok.error()).isFalse();
        assertThat(ok.action().getDueAt()).isEqualTo(LocalDate.of(2026, 10, 23));

        var bad = executor.execute(userId, terminal, json.readTree("{\"description\":\"x\",\"due_date\":\"vendredi\"}"));
        assertThat(bad.error()).isTrue();
        assertThat(bad.content()).contains("AAAA-MM-JJ");
    }
}
