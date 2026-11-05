package fr.claudegateway.jarvis.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.runner.RunnerStatusService;

/** F-247 / SF-247-11 — l'état connu joint au message d'Opus en mode Jarvis, poste éteint ; inchangé à l'octet sinon. */
class JarvisStateNoteTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();
    private JarvisStateBlock block;
    private RunnerStatusService status;
    private JarvisStateNote note;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        block = mock(JarvisStateBlock.class);
        status = mock(RunnerStatusService.class);
        note = new JarvisStateNote(block, status, JarvisStateProperties.defaults());
        workspace = Workspace.builder().id(UUID.randomUUID()).userId(alice).name("SSO").hostId(host).build();
    }

    private static RunnerStatusService.RunnerStatus connected(boolean on) {
        return new RunnerStatusService.RunnerStatus(on, true, null, null, null, null, null, false);
    }

    @Test
    @DisplayName("poste éteint : la note fixe puis le bloc, APRÈS le message")
    void offlineAppendsBlock() {
        when(status.hostStatus(alice, host)).thenReturn(connected(false));
        when(block.ready(eq(alice), eq(host), any())).thenReturn(new JarvisStateBlock.Selected(
                "## État connu — client CAGIP\n- PROJ-1 : En revue (vu hier 17:42, tour)", List.of(), 0L));
        String out = note.appendTo(alice, workspace, "Où en est PROJ-1 ?", false);
        assertThat(out).startsWith("Où en est PROJ-1 ?" + JarvisStateNote.PREFACE).endsWith("(vu hier 17:42, tour)");
    }

    @Test
    @DisplayName("poste connecté, présentation, sujet sans poste, bloc vide : message identique à l'octet")
    void unchangedOtherwise() {
        String message = "Où en est PROJ-1 ?";
        when(status.hostStatus(alice, host)).thenReturn(connected(true));
        assertThat(note.appendTo(alice, workspace, message, false)).isSameAs(message);
        assertThat(note.appendTo(alice, workspace, message, true)).isSameAs(message);
        assertThat(note.appendTo(alice, Workspace.builder().userId(alice).name("h").build(), message, false))
                .isSameAs(message);
        when(status.hostStatus(alice, host)).thenReturn(connected(false));
        when(block.ready(any(), any(), any())).thenReturn(JarvisStateBlock.Selected.EMPTY);
        assertThat(note.appendTo(alice, workspace, message, false)).isSameAs(message);
        JarvisStateNote off = new JarvisStateNote(block, status, new JarvisStateProperties(null, null, null, null,
                null, false, null));
        assertThat(off.appendTo(alice, workspace, message, false)).isSameAs(message);
        verify(block, never()).compose(any());
    }
}
