package fr.claudegateway.atelier.routine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.routine.RoutineDtos.RoutineRequest;
import fr.claudegateway.atelier.routine.RoutineDtos.RoutineView;

/** F-183 / SF-183-01 — l'agent propose une routine, jamais au terminal du poste. */
class RoutineToolExecutorTest {

    private final UUID userId = UUID.randomUUID();
    private final RoutineToolCatalog catalog = new RoutineToolCatalog();
    private final RoutineService service = mock(RoutineService.class);
    private final RoutineToolExecutor executor = new RoutineToolExecutor(catalog, service);
    private final ObjectMapper mapper = new ObjectMapper();

    private Workspace subject() {
        return Workspace.builder().id(UUID.randomUUID()).userId(userId).name("cagip")
                .executionTarget(WorkspaceExecutionTarget.RUNNER).hostId(UUID.randomUUID()).build();
    }

    @Test
    void declaredOnlyInASubjectOnAHost() {
        assertThat(catalog.toolsFor(userId, subject())).hasSize(1);
        Workspace host = subject();
        host.setHostTerminal(true);
        assertThat(catalog.toolsFor(userId, host)).isEmpty();
        Workspace hosted = subject();
        hosted.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        assertThat(catalog.toolsFor(userId, hosted)).isEmpty();
        assertThat(catalog.toolsFor(UUID.randomUUID(), subject())).isEmpty();
    }

    @Test
    void proposesAWeeklyRoutine() {
        Workspace ws = subject();
        when(service.propose(eq(userId), eq(ws), any())).thenReturn(new RoutineView(UUID.randomUUID(), ws.getId(),
                "CR semaine", "x", "WEEKLY", 5, "17:00", "Europe/Paris", new BigDecimal("3.00"), false, true,
                "AGENT", null, null, null));
        ObjectNode input = mapper.createObjectNode().put("nom", "CR semaine").put("consigne", "prépare le CR")
                .put("rythme", "hebdo").put("jour", "vendredi").put("heure", "17:00").put("budget_usd", 3);
        RoutineToolExecutor.Outcome outcome = executor.execute(userId, ws, RoutineToolCatalog.PROPOSE, input);
        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("N'EST PAS active");
        ArgumentCaptor<RoutineRequest> request = ArgumentCaptor.forClass(RoutineRequest.class);
        verify(service).propose(eq(userId), eq(ws), request.capture());
        assertThat(request.getValue().cadence()).isEqualTo("WEEKLY");
        assertThat(request.getValue().dayOfWeek()).isEqualTo(5);
        assertThat(request.getValue().maxRunUsd()).isEqualByComparingTo("3");
    }

    @Test
    void weekdaysMapsAndInvalidInputIsAnError() {
        Workspace ws = subject();
        when(service.propose(eq(userId), eq(ws), any()))
                .thenThrow(new RoutineExceptions.InvalidRoutineException("L'heure s'écrit HH:mm (ex. 08:00)."));
        ObjectNode input = mapper.createObjectNode().put("nom", "Matin").put("consigne", "CR")
                .put("rythme", "jours_ouvres").put("heure", "8h");
        RoutineToolExecutor.Outcome outcome = executor.execute(userId, ws, RoutineToolCatalog.PROPOSE, input);
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("HH:mm");
    }

    @Test
    void refusedAtTheHostTerminal() {
        Workspace host = subject();
        host.setHostTerminal(true);
        RoutineToolExecutor.Outcome outcome = executor.execute(userId, host, RoutineToolCatalog.PROPOSE,
                mapper.createObjectNode());
        assertThat(outcome.error()).isTrue();
        verify(service, never()).propose(any(), any(), any());
    }
}
