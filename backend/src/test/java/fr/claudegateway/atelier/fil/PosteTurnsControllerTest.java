package fr.claudegateway.atelier.fil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.auth.CurrentUser;

/** La route du fil des tâches (F-191 / SF-191-01) : identité du principal, accès terminal d'abord. */
class PosteTurnsControllerTest {

    private final PosteTurnsService service = mock(PosteTurnsService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final AtelierAccessService access = mock(AtelierAccessService.class);
    private final PosteTurnsController controller = new PosteTurnsController(service, currentUser, access);

    private final UUID user = UUID.randomUUID();
    private final UUID terminal = UUID.randomUUID();

    @Test
    void rendLesToursDuPosteDuPrincipal() {
        PosteTurnsResponse expected = new PosteTurnsResponse(UUID.randomUUID(), 1L, List.of());
        when(currentUser.requireId()).thenReturn(user);
        when(service.turnsOfPoste(user, terminal)).thenReturn(expected);

        assertThat(controller.turns(terminal)).isSameAs(expected);
        verify(access).requireTerminalAccess(terminal);
    }

    @Test
    void sansAccesTerminalRienNestLu() {
        doThrow(new IllegalStateException("refus")).when(access).requireTerminalAccess(terminal);

        assertThatThrownBy(() -> controller.turns(terminal)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(service);
    }

    @Test
    void terminalDAutruiIntrouvable() {
        when(currentUser.requireId()).thenReturn(user);
        when(service.turnsOfPoste(user, terminal)).thenThrow(new WorkspaceNotFoundException("x"));

        assertThatThrownBy(() -> controller.turns(terminal)).isInstanceOf(WorkspaceNotFoundException.class);
    }
}
