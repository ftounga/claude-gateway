package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * Projets qui vivent <b>sur la machine</b> de l'utilisateur (F-38 / SF-38-15) : création sans
 * archive ni dépôt, racine déclarée par le runner, et les gestes qui n'ont pas de sens ici.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspaceServiceLocalTest {

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceStorage storage;
    @Mock private AtelierMessageRepository messageRepository;

    private WorkspaceService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = newService();
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** Construit le service avec les collaborateurs simulés, quel que soit l'ordre du constructeur. */
    private WorkspaceService newService() {
        return new WorkspaceService(workspaceRepository, storage,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true), messageRepository);
    }

    private Workspace localWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.LOCAL);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        return workspace;
    }

    @Test
    void createsALocalProjectWithoutAnyStorage() {
        Workspace created = service.createLocal(userId, "  runner-claude  ");

        assertThat(created.getSource()).isEqualTo(WorkspaceSource.LOCAL);
        assertThat(created.getName()).isEqualTo("runner-claude");
        // La cible est imposée : un projet local en bac à sable ouvrirait une session sur un dossier
        // vide et laisserait croire que le travail a lieu quelque part (D3).
        assertThat(created.executionTargetOrDefault()).isEqualTo(WorkspaceExecutionTarget.RUNNER);
        // L'exécution est autorisée par défaut (F-47 / SF-47-04, décision du PO du 2026-09-10 qui
        // tranche OQ-14) : la première commande n'attend plus un clic sur une machine que
        // l'utilisateur a lui-même connectée.
        assertThat(created.isAgentAskBeforeBash()).isFalse();
        // Rien n'est alloué de ce dont on ne se servira jamais (D4).
        verify(storage, never()).putFile(any(), any(), any());
    }

    @Test
    void refusesAnEmptyOrOversizedName() {
        assertThatThrownBy(() -> service.createLocal(userId, "   "))
                .isInstanceOf(InvalidArchiveException.class);
        assertThatThrownBy(() -> service.createLocal(userId, "x".repeat(256)))
                .isInstanceOf(InvalidArchiveException.class);
    }


    @Test
    void refusesToSwitchALocalProjectToTheHostedSandbox() {
        when(workspaceRepository.findByIdAndUserId(workspaceId, userId))
                .thenReturn(java.util.Optional.of(localWorkspace()));

        assertThatThrownBy(() -> service.setExecutionTarget(userId, workspaceId,
                WorkspaceExecutionTarget.SANDBOX))
                .isInstanceOf(LocalWorkspaceException.class)
                .hasMessageContaining("runner");
    }

    @Test
    void staysOnRunnerWhenTheTargetIsSetToRunnerAgain() {
        when(workspaceRepository.findByIdAndUserId(workspaceId, userId))
                .thenReturn(java.util.Optional.of(localWorkspace()));

        Workspace result = service.setExecutionTarget(userId, workspaceId,
                WorkspaceExecutionTarget.RUNNER);

        assertThat(result.executionTargetOrDefault()).isEqualTo(WorkspaceExecutionTarget.RUNNER);
    }

    @Test
    void doesNotArmTheConfirmationGateWhenSwitchingToRunner() {
        when(workspaceRepository.findByIdAndUserId(workspaceId, userId))
                .thenReturn(java.util.Optional.of(localWorkspace()));

        Workspace result = service.setExecutionTarget(userId, workspaceId,
                WorkspaceExecutionTarget.RUNNER);

        // La bascule armait la porte à chaque passage en cible RUNNER (SF-38-08, D7). Le laisser
        // en place rendrait le nouveau défaut inopérant dès la première bascule (F-47 / SF-47-04).
        assertThat(result.isAgentAskBeforeBash()).isFalse();
    }

    @Test
    void leavesTheConfirmationGateArmedWhenTheUserHadArmedIt() {
        Workspace armed = localWorkspace();
        armed.setAgentAskBeforeBash(true);
        when(workspaceRepository.findByIdAndUserId(workspaceId, userId))
                .thenReturn(java.util.Optional.of(armed));

        Workspace result = service.setExecutionTarget(userId, workspaceId,
                WorkspaceExecutionTarget.RUNNER);

        // On ne désarme pas plus qu'on n'arme dans le dos de l'utilisateur : le réglage est le sien.
        assertThat(result.isAgentAskBeforeBash()).isTrue();
    }

    @Test
    void refusesToWriteAFileIntoStorageForALocalProject() {
        when(workspaceRepository.findByIdAndUserId(workspaceId, userId))
                .thenReturn(java.util.Optional.of(localWorkspace()));

        assertThatThrownBy(() -> service.writeFile(userId, workspaceId, "a.txt", "contenu"))
                .isInstanceOf(LocalWorkspaceException.class);
        // Refus AVANT toute écriture : rien n'est parti vers le stockage.
        verify(storage, never()).putFile(any(), any(), any());
    }
}
