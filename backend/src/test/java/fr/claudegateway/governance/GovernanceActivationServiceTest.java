package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import fr.claudegateway.runner.host.RunnerHostNotFoundException;

/**
 * F-51 / SF-51-02, regrainé par F-75 / SF-75-01 — activer, désactiver, embarquer les défauts
 * <b>sur un poste</b>. Les règles qui comptent : on n'active que ce qu'on a retenu, on n'écrit jamais
 * sur le poste d'un autre, rien n'est dupliqué ni détruit par un geste rejoué, et il n'existe aucun
 * moyen d'activer sur un dossier seul.
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
    private GovernanceHostScope hostScope;

    private GovernanceActivationService service;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(hostId);
    private GovernancePackage pkg;

    @BeforeEach
    void setUp() {
        service = new GovernanceActivationService(activations, selectionService, packageService,
                hostScope);
        pkg = GovernancePackage.builder().id(UUID.randomUUID()).slug("p").name("P").version(3)
                .published(true).rules("r").build();
        when(packageService.requirePublished(pkg.getId())).thenReturn(pkg);
        when(activations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(selectionService.isSelected(any(), any())).thenReturn(true);
        when(activations.findByUserIdAndHostIdAndPackageId(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(hostScope.projectsOf(any(), any())).thenReturn(List.of());
        when(hostScope.nameOf(any(), any())).thenReturn("EDENRED");
    }

    @Test
    @DisplayName("activer un paquet retenu crée une activation PENDING sur le POSTE")
    void activateCreatesPendingOnHost() {
        GovernanceActivation activation = service.activate(alice, host, pkg.getId());

        assertThat(activation.getStatus()).isEqualTo(GovernanceActivationStatus.PENDING);
        assertThat(activation.getAppliedVersion()).isEqualTo(3);
        assertThat(activation.getUserId()).isEqualTo(alice);
        assertThat(activation.getHostId()).isEqualTo(hostId);
    }

    @Test
    @DisplayName("activer un paquet non retenu est refusé")
    void refusesActivatingUnselectedPackage() {
        when(selectionService.isSelected(alice, pkg.getId())).thenReturn(false);

        assertThatThrownBy(() -> service.activate(alice, host, pkg.getId()))
                .isInstanceOf(GovernancePackageConflictException.class)
                .hasMessageContaining("catalogue");
        verify(activations, never()).save(any());
    }

    @Test
    @DisplayName("le poste d'un autre est introuvable, et rien n'est écrit")
    void refusesActivatingOnSomeoneElsesHost() {
        // L'isolation est portée par le résolveur : une référence qu'il refuse n'atteint jamais
        // le service. On vérifie qu'elle rend « introuvable », et non « interdit ».
        when(hostScope.require(bob, hostId.toString()))
                .thenThrow(new RunnerHostNotFoundException("Poste introuvable."));

        assertThatThrownBy(() -> hostScope.require(bob, hostId.toString()))
                .isInstanceOf(RunnerHostNotFoundException.class);
        verify(activations, never()).save(any());
    }

    @Test
    @DisplayName("réactiver rend l'activation existante, sans doublon ni régression de version")
    void activateIsIdempotent() {
        GovernanceActivation existing = GovernanceActivation.builder()
                .id(UUID.randomUUID()).userId(alice).hostId(hostId).packageId(pkg.getId())
                .appliedVersion(2).status(GovernanceActivationStatus.APPLIED).build();
        when(activations.findByUserIdAndHostIdAndPackageId(alice, hostId, pkg.getId()))
                .thenReturn(Optional.of(existing));

        GovernanceActivation activation = service.activate(alice, host, pkg.getId());

        assertThat(activation).isSameAs(existing);
        assertThat(activation.getAppliedVersion()).isEqualTo(2);
        verify(activations, never()).save(any());
    }

    @Test
    @DisplayName("désactiver retire l'activation, et reste un succès si elle n'existe pas")
    void deactivateIsIdempotent() {
        service.deactivate(alice, host, pkg.getId());

        verify(activations).deleteByUserIdAndHostIdAndPackageId(alice, hostId, pkg.getId());
    }

    @Test
    @DisplayName("l'embarquement crée une activation pour chaque défaut, et rien pour les autres")
    void embarkCreatesOnlyDefaults() {
        GovernanceSelection isDefault = GovernanceSelection.builder()
                .userId(alice).packageId(pkg.getId()).defaultApplied(true).build();
        when(selectionService.defaults(alice)).thenReturn(List.of(isDefault));

        List<GovernanceActivation> embarked = service.embarkDefaults(alice, host);

        assertThat(embarked).singleElement().satisfies(activation -> {
            assertThat(activation.getPackageId()).isEqualTo(pkg.getId());
            assertThat(activation.getHostId()).isEqualTo(hostId);
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
                .id(UUID.randomUUID()).userId(alice).hostId(hostId).packageId(pkg.getId())
                .appliedVersion(3).status(GovernanceActivationStatus.APPLIED).build();
        when(activations.findByUserIdAndHostIdAndPackageId(alice, hostId, pkg.getId()))
                .thenReturn(Optional.of(existing));

        assertThat(service.embarkDefaults(alice, host)).containsExactly(existing);
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

        assertThat(service.embarkDefaults(alice, host)).isEmpty();
        verify(activations, never()).save(any());
    }

    @Test
    @DisplayName("l'état d'un poste signale une version dépassée sans rien mettre à jour")
    void describeReportsOutdatedVersion() {
        GovernanceActivation activation = GovernanceActivation.builder()
                .id(UUID.randomUUID()).userId(alice).hostId(hostId).packageId(pkg.getId())
                .appliedVersion(2).status(GovernanceActivationStatus.APPLIED).build();
        when(activations.findByUserIdAndHostIdOrderByCreatedAtAsc(alice, hostId))
                .thenReturn(List.of(activation));
        when(packageService.require(pkg.getId())).thenReturn(pkg);
        when(packageService.filesOf(pkg.getId())).thenReturn(List.of());
        when(packageService.publicView(any(), any())).thenReturn(
                new fr.claudegateway.governance.dto.GovernancePackageView(pkg.getId(), pkg.getSlug(),
                        pkg.getName(), null, pkg.getVersion(), pkg.getRules(), List.of(), List.of()));
        when(selectionService.all(alice)).thenReturn(List.of());

        var view = service.describe(alice, host);

        assertThat(view.ref()).isEqualTo(hostId.toString());
        assertThat(view.id()).isEqualTo(hostId);
        assertThat(view.active()).singleElement().satisfies(active -> {
            assertThat(active.appliedVersion()).isEqualTo(2);
            assertThat(active.outdated()).isTrue();
        });
        assertThat(view.available()).isEmpty();
        verify(activations, never()).save(any());
    }

    @Test
    @DisplayName("le poste « Hébergé » est gouvernable, et ne rend jamais d'identifiant")
    void hostedIsGovernableWithoutPublicId() {
        when(activations.findByUserIdAndHostIdOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());
        when(selectionService.all(alice)).thenReturn(List.of());
        when(hostScope.nameOf(alice, GovernanceHostRef.HOSTED)).thenReturn("Hébergé");

        var view = service.describe(alice, GovernanceHostRef.HOSTED);

        assertThat(view.ref()).isEqualTo("hosted");
        assertThat(view.virtual()).isTrue();
        // Décision F-71 : ce poste est une VUE ; il n'a pas d'identifiant public, et n'en aura pas.
        assertThat(view.id()).isNull();
    }

    @Test
    @DisplayName("un projet est gouverné par les activations de SON poste")
    void workspaceIsGovernedByItsHost() {
        UUID workspaceId = UUID.randomUUID();
        when(hostScope.hostOf(alice, workspaceId)).thenReturn(host);
        GovernanceActivation activation = GovernanceActivation.builder()
                .id(UUID.randomUUID()).userId(alice).hostId(hostId).packageId(pkg.getId())
                .appliedVersion(3).status(GovernanceActivationStatus.APPLIED).build();
        when(activations.findByUserIdAndHostIdOrderByCreatedAtAsc(alice, hostId))
                .thenReturn(List.of(activation));

        assertThat(service.activeOnWorkspace(alice, workspaceId)).containsExactly(activation);
    }

    @Test
    @DisplayName("supprimer un poste efface sa gouvernance")
    void forgettingHostClearsActivations() {
        service.forgetHost(alice, hostId);

        verify(activations).deleteByUserIdAndHostId(alice, hostId);
    }
}
