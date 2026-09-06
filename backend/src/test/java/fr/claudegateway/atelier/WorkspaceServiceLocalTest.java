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
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null), messageRepository);
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
        assertThat(created.isAgentAskBeforeBash()).isTrue();
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
    void keepsOnlyTheLastSegmentOfWhatTheRunnerDeclares() {
        // Le runner n'envoie qu'un nom, mais on ne fait pas confiance à un client pour ça : un
        // chemin absolu est réduit ici, et n'est jamais stocké.
        assertThat(WorkspaceService.lastSegment("/home/francky/dev/runner-claude")).isEqualTo("runner-claude");
        assertThat(WorkspaceService.lastSegment("C:\\Users\\f\\projets\\demo")).isEqualTo("demo");
        assertThat(WorkspaceService.lastSegment("~/dev/runner-claude/")).isEqualTo("runner-claude");
        assertThat(WorkspaceService.lastSegment("runner-claude")).isEqualTo("runner-claude");
        assertThat(WorkspaceService.lastSegment("   ")).isNull();
        assertThat(WorkspaceService.lastSegment(null)).isNull();
        assertThat(WorkspaceService.lastSegment("/" + "y".repeat(300))).hasSize(255);
    }

    @Test
    void recordsTheRootNameDeclaredAtPairing() {
        Workspace workspace = localWorkspace();
        when(workspaceRepository.findById(workspaceId)).thenReturn(java.util.Optional.of(workspace));

        service.recordRunnerRootName(workspaceId, "/home/francky/dev/runner-claude");

        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(saved.capture());
        assertThat(saved.getValue().getRunnerRootName()).isEqualTo("runner-claude");
    }

    @Test
    void ignoresAnAbsentRootNameSoOlderRunnersStillPair() {
        service.recordRunnerRootName(workspaceId, null);

        verify(workspaceRepository, never()).save(any(Workspace.class));
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
    void refusesToWriteAFileIntoStorageForALocalProject() {
        when(workspaceRepository.findByIdAndUserId(workspaceId, userId))
                .thenReturn(java.util.Optional.of(localWorkspace()));

        assertThatThrownBy(() -> service.writeFile(userId, workspaceId, "a.txt", "contenu"))
                .isInstanceOf(LocalWorkspaceException.class);
        // Refus AVANT toute écriture : rien n'est parti vers le stockage.
        verify(storage, never()).putFile(any(), any(), any());
    }
}
