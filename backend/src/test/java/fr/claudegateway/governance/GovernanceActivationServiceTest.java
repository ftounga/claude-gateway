package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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

import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;

/**
 * F-51 / SF-51-02 — activer, désactiver, embarquer les défauts. Les trois règles qui comptent : on
 * n'active que ce qu'on a retenu, on n'écrit jamais sur le projet d'un autre, et rien n'est dupliqué
 * ni détruit par un geste rejoué.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceActivationServiceTest {

    @Mock
    private GovernanceActivationRepository activations;
    @Mock
    private GovernanceSelectionService selectionService;
    @Mock
    private GovernancePackageService packageService;
    @Mock
    private WorkspaceService workspaceService;

    private GovernanceActivationService service;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID workspace = UUID.randomUUID();
    private GovernancePackage pkg;

    @BeforeEach
    void setUp() {
        service = new GovernanceActivationService(activations, selectionService, packageService,
                workspaceService);
        pkg = GovernancePackage.builder().id(UUID.randomUUID()).slug("p").name("P").version(3)
                .published(true).rules("r").build();
        when(packageService.requirePublished(pkg.getId())).thenReturn(pkg);
        when(activations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(selectionService.isSelected(any(), any())).thenReturn(true);
        when(activations.findByUserIdAndWorkspaceIdAndPackageId(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("activer un paquet retenu crée une activation PENDING à la version courante")
    void activateCreatesPendingAtCurrentVersion() {
        GovernanceActivation activation = service.activate(alice, workspace, pkg.getId());

        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.PENDING);
        assertThat(activation.getAppliedVersion()).isEqualTo(3);
        assertThat(activation.getUserId()).isEqualTo(alice);
        assertThat(activation.getWorkspaceId()).isEqualTo(workspace);
        // Isolation : le projet est vérifié comme possédé AVANT toute écriture.
        verify(workspaceService).requireOwned(alice, workspace);
    }

    @Test
    @DisplayName("activer un paquet non retenu est refusé")
    void refusesActivatingUnselectedPackage() {
        when(selectionService.isSelected(alice, pkg.getId())).thenReturn(false);

        assertThatThrownBy(() -> service.activate(alice, workspace, pkg.getId()))
                .isInstanceOf(GovernancePackageConflictException.class)
                .hasMessageContaining("catalogue");
        verify(activations, never()).save(any());
    }

    @Test
    @DisplayName("activer sur le projet d'un autre n'écrit rien")
    void refusesActivatingOnSomeoneElsesProject() {
        when(workspaceService.requireOwned(eq(bob), eq(workspace)))
                .thenThrow(new WorkspaceNotFoundException("Projet introuvable."));

        assertThatThrownBy(() -> service.activate(bob, workspace, pkg.getId()))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(activations, never()).save(any());
    }

    @Test
    @DisplayName("réactiver rend l'activation existante, sans doublon ni régression de version")
    void activateIsIdempotent() {
        GovernanceActivation existing = GovernanceActivation.builder()
                .id(UUID.randomUUID()).userId(alice).workspaceId(workspace).packageId(pkg.getId())
                .appliedVersion(2).status(GovernanceActivationStatus.APPLIED).build();
        when(activations.findByUserIdAndWorkspaceIdAndPackageId(alice, workspace, pkg.getId()))
                .thenReturn(Optional.of(existing));

        GovernanceActivation activation = service.activate(alice, workspace, pkg.getId());

        assertThat(activation).isSameAs(existing);
        assertThat(activation.getAppliedVersion()).isEqualTo(2);
        verify(activations, never()).save(any());
    }

    @Test
    @DisplayName("désactiver retire l'activation, et reste un succès si elle n'existe pas")
    void deactivateIsIdempotent() {
        service.deactivate(alice, workspace, pkg.getId());

        verify(workspaceService).requireOwned(alice, workspace);
        verify(activations).deleteByUserIdAndWorkspaceIdAndPackageId(alice, workspace, pkg.getId());
    }

    @Test
    @DisplayName("l'embarquement crée une activation pour chaque défaut, et rien pour les autres")
    void embarkCreatesOnlyDefaults() {
        GovernanceSelection isDefault = GovernanceSelection.builder()
                .userId(alice).packageId(pkg.getId()).defaultApplied(true).build();
        when(selectionService.defaults(alice)).thenReturn(List.of(isDefault));

        List<GovernanceActivation> embarked = service.embarkDefaults(alice, workspace);

        assertThat(embarked).singleElement().satisfies(activation -> {
            assertThat(activation.getPackageId()).isEqualTo(pkg.getId());
            assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.PENDING);
        });
    }

    @Test
    @DisplayName("l'embarquement rejoué ne duplique pas")
    void embarkIsIdempotent() {
        GovernanceSelection isDefault = GovernanceSelection.builder()
                .userId(alice).packageId(pkg.getId()).defaultApplied(true).build();
        when(selectionService.defaults(alice)).thenReturn(List.of(isDefault));
        GovernanceActivation existing = GovernanceActivation.builder()
                .id(UUID.randomUUID()).userId(alice).workspaceId(workspace).packageId(pkg.getId())
                .appliedVersion(3).status(GovernanceActivationStatus.APPLIED).build();
        when(activations.findByUserIdAndWorkspaceIdAndPackageId(alice, workspace, pkg.getId()))
                .thenReturn(Optional.of(existing));

        assertThat(service.embarkDefaults(alice, workspace)).containsExactly(existing);
        verify(activations, never()).save(any());
    }

    @Test
    @DisplayName("un défaut dépublié entre-temps est ignoré, sans faire échouer la création")
    void embarkIgnoresUnpublishedDefault() {
        UUID gone = UUID.randomUUID();
        GovernanceSelection isDefault = GovernanceSelection.builder()
                .userId(alice).packageId(gone).defaultApplied(true).build();
        when(selectionService.defaults(alice)).thenReturn(List.of(isDefault));
        when(packageService.requirePublished(gone))
                .thenThrow(new GovernancePackageNotFoundException("Paquet introuvable."));

        assertThat(service.embarkDefaults(alice, workspace)).isEmpty();
        verify(activations, never()).save(any());
    }

    @Test
    @DisplayName("l'état d'un projet signale une version dépassée sans rien mettre à jour")
    void describeReportsOutdatedVersion() {
        GovernanceActivation activation = GovernanceActivation.builder()
                .id(UUID.randomUUID()).userId(alice).workspaceId(workspace).packageId(pkg.getId())
                .appliedVersion(2).status(GovernanceActivationStatus.APPLIED).build();
        when(activations.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(alice, workspace))
                .thenReturn(List.of(activation));
        when(packageService.require(pkg.getId())).thenReturn(pkg);
        when(packageService.filesOf(pkg.getId())).thenReturn(List.of());
        when(packageService.publicView(any(), any())).thenReturn(
                new fr.claudegateway.governance.dto.GovernancePackageView(pkg.getId(), pkg.getSlug(),
                        pkg.getName(), null, pkg.getVersion(), pkg.getRules(), List.of(), List.of()));
        when(selectionService.all(alice)).thenReturn(List.of());

        var view = service.describe(alice, workspace);

        assertThat(view.active()).singleElement().satisfies(active -> {
            assertThat(active.appliedVersion()).isEqualTo(2);
            assertThat(active.outdated()).isTrue();
        });
        assertThat(view.available()).isEmpty();
        verify(activations, never()).save(any());
    }
}
