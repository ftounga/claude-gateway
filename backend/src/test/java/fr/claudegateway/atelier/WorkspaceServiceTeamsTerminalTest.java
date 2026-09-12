package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * <b>Le terminal Teams</b> (F-89 / SF-89-01) : un terminal comme les autres, rattaché au poste dont
 * on observe le navigateur.
 *
 * <p>Ce que ces tests tiennent : les <b>valeurs initiales</b>, l'<b>idempotence</b>, le fait que ce
 * <b>n'est pas un projet</b> — et, la plus importante des quatre, que le terminal Teams et le
 * terminal du poste sont <b>deux lignes distinctes</b>. Les confondre ferait apparaître des cartes
 * de réunion dans le terminal du poste, ce que la règle non négociable du cadrage interdit.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspaceServiceTeamsTerminalTest {

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceStorage storage;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditRepository auditRepository;

    private WorkspaceService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WorkspaceService(workspaceRepository, storage,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true),
                messageRepository, auditRepository,
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));
        when(workspaceRepository.findFirstByUserIdAndHostIdAndTeamsTerminalTrue(userId, hostId))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("c'est un terminal comme les autres, et il porte sa marque")
    void theTeamsTerminalIsATerminalLikeAnyOther() {
        Workspace terminal = service.openTeamsTerminal(userId, hostId);

        assertThat(terminal.getUserId()).isEqualTo(userId);
        assertThat(terminal.getHostId()).isEqualTo(hostId);
        assertThat(terminal.getName()).isEqualTo(WorkspaceService.TEAMS_TERMINAL_NAME);
        // Pas de projet : on lit Teams, pas des fichiers.
        assertThat(terminal.getProjectPath()).isEmpty();
        assertThat(terminal.isTeamsTerminal()).isTrue();
        // « Comme les autres » : mêmes valeurs qu'un projet local, donc mêmes tours, même fil,
        // même place au registre (F-70) et même relevé d'usage (F-61) — sans une ligne de plus.
        assertThat(terminal.getSource()).isEqualTo(WorkspaceSource.LOCAL);
        // La liaison Teams n'existe que sur la machine : la cible ne peut pas être le bac à sable.
        assertThat(terminal.getExecutionTarget()).isEqualTo(WorkspaceExecutionTarget.RUNNER);
    }

    @Test
    @DisplayName("ce n'est PAS le terminal du poste — deux marques, deux lignes")
    void theTeamsTerminalIsNotTheHostTerminal() {
        Workspace terminal = service.openTeamsTerminal(userId, hostId);

        assertThat(terminal.isHostTerminal())
                .as("un terminal Teams n'est pas le terminal du poste : le confondre ferait "
                        + "apparaître des cartes de réunion dans un terminal qui doit rester textuel")
                .isFalse();
    }

    @Test
    void askingTwiceGivesTheSameTerminal() {
        Workspace existing = existingTerminal();
        when(workspaceRepository.findFirstByUserIdAndHostIdAndTeamsTerminalTrue(userId, hostId))
                .thenReturn(Optional.of(existing));

        Workspace terminal = service.openTeamsTerminal(userId, hostId);

        assertThat(terminal).isSameAs(existing);
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    @DisplayName("ce n'est pas un projet : listByHost l'exclut, comme le terminal du poste")
    void theTeamsTerminalIsNotAProject() {
        service.listByHost(userId, hostId);

        verify(workspaceRepository)
                .findByUserIdAndHostIdAndHostTerminalFalseAndTeamsTerminalFalse(userId, hostId);
    }

    @Test
    void deletingTheTeamsTerminalIsSilentWhenThereIsNone() {
        service.deleteTeamsTerminal(userId, hostId);

        verify(workspaceRepository, never()).delete(any(Workspace.class));
    }

    @Test
    @DisplayName("supprimer le poste supprime son terminal Teams : sans machine, il n'observe rien")
    void deletingTheTeamsTerminalRemovesIt() {
        Workspace existing = existingTerminal();
        when(workspaceRepository.findFirstByUserIdAndHostIdAndTeamsTerminalTrue(userId, hostId))
                .thenReturn(Optional.of(existing));
        when(workspaceRepository.findByIdAndUserId(existing.getId(), userId))
                .thenReturn(Optional.of(existing));

        service.deleteTeamsTerminal(userId, hostId);

        verify(workspaceRepository).delete(existing);
    }

    private Workspace existingTerminal() {
        return Workspace.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .hostId(hostId)
                .name(WorkspaceService.TEAMS_TERMINAL_NAME)
                .projectPath("")
                .source(WorkspaceSource.LOCAL)
                .executionTarget(WorkspaceExecutionTarget.RUNNER)
                .teamsTerminal(true)
                .build();
    }
}
