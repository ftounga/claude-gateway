package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * <b>Le terminal du poste</b> (F-74 / SF-74-01) : un terminal comme les autres, rattaché à la
 * machine plutôt qu'à un projet.
 *
 * <p>Ce que ces tests tiennent : les <b>valeurs initiales</b> (c'est là que se joue « un terminal
 * comme les autres » — cible runner, porte de confirmation armée, racine du poste),
 * l'<b>idempotence</b> (deux appels, un seul terminal), et surtout le fait que ce <b>n'est pas un
 * projet</b> — trois lectures en dépendent, décrites dans le cadrage F-74 / D3.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspaceServiceHostTerminalTest {

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
        when(workspaceRepository.findFirstByUserIdAndHostIdAndHostTerminalTrue(userId, hostId))
                .thenReturn(Optional.empty());
    }

    @Test
    void theHostTerminalIsATerminalLikeAnyOther() {
        Workspace terminal = service.openHostTerminal(userId, hostId);

        assertThat(terminal.getUserId()).isEqualTo(userId);
        assertThat(terminal.getHostId()).isEqualTo(hostId);
        assertThat(terminal.getName()).isEqualTo(WorkspaceService.HOST_TERMINAL_NAME);
        // La RACINE du poste : c'est là qu'on clone le premier jour, quand il n'y a rien.
        assertThat(terminal.getProjectPath()).isEmpty();
        assertThat(terminal.isHostTerminal()).isTrue();
        // « Comme les autres » se vérifie ici : mêmes valeurs qu'un projet local.
        assertThat(terminal.getSource()).isEqualTo(WorkspaceSource.LOCAL);
        assertThat(terminal.getExecutionTarget()).isEqualTo(WorkspaceExecutionTarget.RUNNER);
        // La porte de confirmation de F-73 / SF-73-02, ARMÉE : un terminal de poste ne desserre
        // rien. C'est la décision P4 du PO, et elle vaut aussi pour lui.
        // SF-73-04 : la porte est DÉSARMÉE par défaut, à titre temporaire (décision du PO du
        // 2026-09-12). Armée, elle rendait toute première commande d'un projet neuf
        // impossible : l'invite d'autorisation ne s'affiche pas, et le tour expirait au bout
        // de 120 s. Ce test rebascule le jour où l'affichage est réparé et la porte réarmée.
        assertThat(terminal.isAgentAskBeforeBash()).isFalse();
    }

    @Test
    void askingTwiceGivesTheSameTerminal() {
        Workspace existing = existingTerminal();
        when(workspaceRepository.findFirstByUserIdAndHostIdAndHostTerminalTrue(userId, hostId))
                .thenReturn(Optional.of(existing));

        Workspace terminal = service.openHostTerminal(userId, hostId);

        assertThat(terminal).isSameAs(existing);
        // Un second terminal de poste couperait la conversation en deux.
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void theHostTerminalIsNotAProject() {
        // `listByHost` rend LES PROJETS. Sans cette exclusion : un projet fantôme sur la carte du
        // poste, l'impossibilité d'ouvrir un vrai projet sur la racine, et un poste que F-69 ne
        // laisserait plus jamais supprimer.
        service.listByHost(userId, hostId);

        verify(workspaceRepository).findByUserIdAndHostIdAndHostTerminalFalse(userId, hostId);
    }

    @Test
    void aRealProjectCanStillBeOpenedOnTheRootBesideTheTerminal() {
        // Le terminal occupe le chemin « » — mais il n'est pas un projet, donc il n'est pas un
        // doublon. F-72 autorise toujours d'ouvrir un projet SUR la racine.
        when(workspaceRepository.findByUserIdAndHostIdAndHostTerminalFalse(userId, hostId))
                .thenReturn(List.of());

        Workspace project = service.openOnHost(userId, hostId, "", "Poste CAGIP");

        assertThat(project.getProjectPath()).isEmpty();
        assertThat(project.isHostTerminal()).isFalse();
        assertThat(project.getName()).isEqualTo("Poste CAGIP");
    }

    @Test
    void deletingTheHostTerminalIsSilentWhenThereIsNone() {
        service.deleteHostTerminal(userId, hostId);

        verify(workspaceRepository, never()).delete(any(Workspace.class));
    }

    @Test
    void deletingTheHostTerminalTakesItsConversationWithIt() {
        Workspace existing = existingTerminal();
        when(workspaceRepository.findFirstByUserIdAndHostIdAndHostTerminalTrue(userId, hostId))
                .thenReturn(Optional.of(existing));
        when(workspaceRepository.findByIdAndUserId(existing.getId(), userId))
                .thenReturn(Optional.of(existing));

        service.deleteHostTerminal(userId, hostId);

        verify(messageRepository).deleteByWorkspaceId(existing.getId());
        verify(auditRepository).deleteByUserIdAndWorkspaceId(userId, existing.getId());
        verify(workspaceRepository).delete(existing);
    }

    private Workspace existingTerminal() {
        Workspace terminal = new Workspace();
        terminal.setId(UUID.randomUUID());
        terminal.setUserId(userId);
        terminal.setHostId(hostId);
        terminal.setName(WorkspaceService.HOST_TERMINAL_NAME);
        terminal.setProjectPath("");
        terminal.setHostTerminal(true);
        return terminal;
    }
}
