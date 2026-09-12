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

/**
 * F-51 / SF-51-02 — retenir un paquet est un geste de bibliothèque : il n'active rien, il n'est
 * jamais dupliqué, et il ne parle que du catalogue de celui qui le fait.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceSelectionServiceTest {

    @Mock
    private GovernanceSelectionRepository selections;
    @Mock
    private GovernanceActivationRepository activations;
    @Mock
    private GovernancePackageService packageService;

    private GovernanceSelectionService service;

    private final UUID alice = UUID.randomUUID();
    private GovernancePackage pkg;

    @BeforeEach
    void setUp() {
        service = new GovernanceSelectionService(selections, activations, packageService);
        pkg = GovernancePackage.builder().id(UUID.randomUUID()).slug("p").name("P").version(1)
                .published(true).rules("r").build();
        when(packageService.requirePublished(pkg.getId())).thenReturn(pkg);
        when(selections.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(selections.findByUserIdAndPackageId(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("retenir un paquet publié crée une entrée de mon catalogue")
    void selectCreatesEntry() {
        GovernanceSelection selection = service.select(alice, pkg.getId(), true);

        assertThat(selection.getUserId()).isEqualTo(alice);
        assertThat(selection.getPackageId()).isEqualTo(pkg.getId());
        assertThat(selection.isDefaultApplied()).isTrue();
    }

    @Test
    @DisplayName("retenir deux fois ne duplique pas : le drapeau est mis à jour")
    void selectIsIdempotent() {
        GovernanceSelection existing = GovernanceSelection.builder()
                .id(UUID.randomUUID()).userId(alice).packageId(pkg.getId()).defaultApplied(true)
                .build();
        when(selections.findByUserIdAndPackageId(alice, pkg.getId())).thenReturn(Optional.of(existing));

        GovernanceSelection selection = service.select(alice, pkg.getId(), false);

        assertThat(selection).isSameAs(existing);
        assertThat(selection.isDefaultApplied()).isFalse();
    }

    @Test
    @DisplayName("un paquet non publié n'existe pas : on ne peut pas le retenir")
    void cannotSelectUnpublishedPackage() {
        UUID draft = UUID.randomUUID();
        when(packageService.requirePublished(draft))
                .thenThrow(new GovernancePackageNotFoundException("Paquet introuvable."));

        assertThatThrownBy(() -> service.select(alice, draft, false))
                .isInstanceOf(GovernancePackageNotFoundException.class);
        verify(selections, never()).save(any());
    }

    @Test
    @DisplayName("retirer de mon catalogue ne touche à aucune activation en cours")
    void deselectLeavesActivationsAlone() {
        service.deselect(alice, pkg.getId());

        verify(selections).deleteByUserIdAndPackageId(alice, pkg.getId());
        verify(activations, never()).deleteByUserIdAndHostIdAndPackageId(any(), any(), any());
    }

    @Test
    @DisplayName("mon catalogue dit, pour chaque paquet, sur combien de MES projets il est actif")
    void listCountsMyActiveProjects() {
        GovernanceSelection selection = GovernanceSelection.builder()
                .id(UUID.randomUUID()).userId(alice).packageId(pkg.getId()).defaultApplied(true)
                .build();
        when(selections.findByUserIdOrderByCreatedAtAsc(alice)).thenReturn(List.of(selection));
        when(packageService.require(pkg.getId())).thenReturn(pkg);
        when(packageService.filesOf(pkg.getId())).thenReturn(List.of());
        when(packageService.publicView(any(), any())).thenReturn(
                new fr.claudegateway.governance.dto.GovernancePackageView(pkg.getId(), pkg.getSlug(),
                        pkg.getName(), null, 1, pkg.getRules(), List.of(), List.of()));
        when(activations.findByUserIdAndPackageId(alice, pkg.getId())).thenReturn(List.of(
                GovernanceActivation.builder().userId(alice).packageId(pkg.getId()).build(),
                GovernanceActivation.builder().userId(alice).packageId(pkg.getId()).build()));

        assertThat(service.list(alice)).singleElement().satisfies(view -> {
            assertThat(view.defaultApplied()).isTrue();
            assertThat(view.activeProjects()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("« est-ce retenu » ne répond que sur mon propre catalogue")
    void isSelectedIsScopedToTheUser() {
        when(selections.findByUserIdAndPackageId(alice, pkg.getId()))
                .thenReturn(Optional.of(GovernanceSelection.builder().userId(alice).build()));

        assertThat(service.isSelected(alice, pkg.getId())).isTrue();
        assertThat(service.isSelected(UUID.randomUUID(), pkg.getId())).isFalse();
    }
}
