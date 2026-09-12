package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
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
 * F-93 / SF-93-01 — <b>où un élément durable se promeut</b>.
 *
 * <p>Ce que ces tests protègent :</p>
 * <ul>
 *   <li>les destinations sont <b>réelles</b> — celles des paquets actifs, jamais une liste gravée
 *       dans le code qui mentirait au premier paquet ajouté ;</li>
 *   <li><b>seul le genre {@code MAP}</b> est une destination : un skill ou un gabarit de projet n'en
 *       est pas une ;</li>
 *   <li>ce service <b>ne lève jamais</b> — un contrôle de fin de tour qui échouerait sur une carte
 *       qu'il n'a pas su lister prendrait le message d'un utilisateur en otage.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceMapDestinationsTest {

    @Mock
    private GovernanceActivationRepository activations;
    @Mock
    private GovernancePackageRepository packages;
    @Mock
    private GovernancePackageFileRepository packageFiles;
    @Mock
    private GovernanceHostScope hostScope;

    private GovernanceMapDestinations destinations;

    private final UUID alice = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(hostId);
    private GovernancePackage pkg;

    @BeforeEach
    void setUp() {
        destinations = new GovernanceMapDestinations(activations, packages, packageFiles, hostScope);
        when(hostScope.hostOf(alice, workspaceId)).thenReturn(host);
        pkg = GovernancePackage.builder().id(UUID.randomUUID()).slug("savoir-durable")
                .name("Le savoir durable").version(3).published(true).build();
        when(packages.findById(pkg.getId())).thenReturn(Optional.of(pkg));
        when(packageFiles.findByPackageIdOrderByPositionAsc(pkg.getId())).thenReturn(List.of(
                file("README.md", GovernanceFileKind.MAP),
                file("acces.md", GovernanceFileKind.MAP),
                file("STATE.md", GovernanceFileKind.TEMPLATE),
                file(".claude/skills/explique.md", GovernanceFileKind.SKILL)));
        when(activations.findByUserIdAndHostIdOrderByCreatedAtAsc(alice, hostId))
                .thenReturn(List.of(activation()));
    }

    private GovernanceActivation activation() {
        return GovernanceActivation.builder().id(UUID.randomUUID()).userId(alice).hostId(hostId)
                .packageId(pkg.getId()).appliedVersion(3).build();
    }

    private static GovernancePackageFile file(String path, GovernanceFileKind kind) {
        return GovernancePackageFile.builder().id(UUID.randomUUID()).path(path).kind(kind)
                .content("# " + path).position(0).build();
    }

    @Test
    @DisplayName("seuls les fichiers de carte sont des destinations")
    void onlyMapFilesAreDestinations() {
        assertThat(destinations.pathsForProject(alice, workspaceId))
                .containsExactly("README.md", "acces.md");
    }

    @Test
    @DisplayName("la même liste sert au poste et au projet : une seule vérité")
    void theHostAndTheProjectSeeTheSameMap() {
        assertThat(destinations.filesOf(alice, host).keySet())
                .containsExactlyElementsOf(destinations.pathsForProject(alice, workspaceId));
    }

    @Test
    @DisplayName("un paquet dépublié n'apporte plus de destination")
    void anUnpublishedPackageBringsNothing() {
        pkg.setPublished(false);

        assertThat(destinations.pathsForProject(alice, workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("deux paquets apportant le même chemin : le premier activé gagne, comme au dépôt")
    void theFirstActivatedWins() {
        GovernancePackage other = GovernancePackage.builder().id(UUID.randomUUID()).slug("autre")
                .name("Autre").version(1).published(true).build();
        when(packages.findById(other.getId())).thenReturn(Optional.of(other));
        when(packageFiles.findByPackageIdOrderByPositionAsc(other.getId()))
                .thenReturn(List.of(file("acces.md", GovernanceFileKind.MAP),
                        file("reseau.md", GovernanceFileKind.MAP)));
        when(activations.findByUserIdAndHostIdOrderByCreatedAtAsc(alice, hostId))
                .thenReturn(List.of(activation(),
                        GovernanceActivation.builder().id(UUID.randomUUID()).userId(alice)
                                .hostId(hostId).packageId(other.getId()).appliedVersion(1).build()));

        assertThat(destinations.pathsForProject(alice, workspaceId))
                .containsExactly("README.md", "acces.md", "reseau.md");
    }

    @Test
    @DisplayName("le projet d'un autre utilisateur ne rend RIEN, et ne lève pas")
    void anotherUsersProjectYieldsNothing() {
        UUID mallory = UUID.randomUUID();
        when(hostScope.hostOf(mallory, workspaceId))
                .thenThrow(new IllegalArgumentException("projet introuvable"));

        assertThat(destinations.pathsForProject(mallory, workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("un couple absent rend une liste vide sans appeler quoi que ce soit")
    void anAbsentIdentityYieldsNothing() {
        assertThat(destinations.pathsForProject(null, workspaceId)).isEmpty();
        assertThat(destinations.pathsForProject(alice, null)).isEmpty();
    }

    @Test
    @DisplayName("la citation retombe sur une formulation générique quand la carte est vide")
    void theGenericWordingIsUsedWhenTheMapIsUnknown() {
        assertThat(GovernanceMapDestinations.cite(List.of()))
                .isEqualTo(GovernanceMapDestinations.GENERIC);
        assertThat(GovernanceMapDestinations.cite(null))
                .isEqualTo(GovernanceMapDestinations.GENERIC);
        assertThat(destinations.citedForProject(alice, workspaceId))
                .isEqualTo("README.md, acces.md");
    }

    @Test
    @DisplayName("la citation est bornée : un refus qui liste trente fichiers ne se corrige plus")
    void theCitationIsBounded() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < GovernanceMapDestinations.MAX_CITED + 5; i++) {
            many.add("fichier-" + i + ".md");
        }

        String cited = GovernanceMapDestinations.cite(many);

        assertThat(cited).endsWith("…");
        assertThat(cited.split(", ")).hasSizeLessThanOrEqualTo(GovernanceMapDestinations.MAX_CITED + 1);
    }
}
