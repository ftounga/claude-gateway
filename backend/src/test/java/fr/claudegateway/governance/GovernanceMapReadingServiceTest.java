package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.governance.GovernanceHostFiles.HostFileRead;
import fr.claudegateway.governance.GovernanceHostFiles.Presence;
import fr.claudegateway.governance.dto.GovernanceMapFileContent;
import fr.claudegateway.governance.dto.GovernanceMapView;

/**
 * F-92 / SF-92-02 — <b>ce que la machine sait</b>, assemblé pour l'écran.
 *
 * <p>Ce que ces tests protègent :</p>
 * <ul>
 *   <li><b>on ne lit que la carte</b> — les chemins lisibles sont ceux des paquets actifs, pas un de
 *       plus : cette route n'est pas un explorateur du disque d'un client ;</li>
 *   <li><b>trois « non » différents</b> — pas une machine / pas gouverné / pas joignable appellent
 *       trois gestes distincts, et les fondre enverrait l'utilisateur au mauvais endroit ;</li>
 *   <li><b>le coût est borné</b> — une machine muette est constatée <b>une fois</b>, pas six.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GovernanceMapReadingServiceTest {

    @Mock
    private GovernanceActivationRepository activations;
    @Mock
    private GovernancePackageRepository packages;
    @Mock
    private GovernancePackageFileRepository packageFiles;
    @Mock
    private GovernanceHostFiles hostFiles;
    @Mock
    private GovernanceHostScope hostScope;

    private GovernanceMapReadingService service;

    private final UUID alice = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(hostId);
    private GovernancePackage pkg;

    @BeforeEach
    void setUp() {
        // Le vrai résolveur de destinations (F-93 / SF-93-01) sur des dépôts simulés : c'est LA
        // MÊME liste qui sert à rendre la carte et à nommer où promouvoir, et la tester deux fois
        // la ferait diverger.
        service = new GovernanceMapReadingService(
                new GovernanceMapDestinations(activations, packages, packageFiles, hostScope),
                hostFiles, hostScope);
        when(hostScope.nameOf(alice, host)).thenReturn("FREE");
        when(hostFiles.supports(host)).thenReturn(true);

        pkg = GovernancePackage.builder().id(UUID.randomUUID()).slug("savoir-durable")
                .name("Le savoir durable").version(3).published(true).build();
        when(packages.findById(pkg.getId())).thenReturn(java.util.Optional.of(pkg));
        when(packageFiles.findByPackageIdOrderByPositionAsc(pkg.getId())).thenReturn(List.of(
                file("README.md", GovernanceFileKind.MAP),
                file("acces.md", GovernanceFileKind.MAP),
                file("STATE.md", GovernanceFileKind.TEMPLATE)));
        when(activations.findByUserIdAndHostIdOrderByCreatedAtAsc(alice, hostId)).thenReturn(List.of(
                GovernanceActivation.builder().id(UUID.randomUUID()).userId(alice).hostId(hostId)
                        .packageId(pkg.getId()).appliedVersion(3).build()));
    }

    private static GovernancePackageFile file(String path, GovernanceFileKind kind) {
        return GovernancePackageFile.builder().id(UUID.randomUUID()).path(path).kind(kind)
                .content("# " + path).build();
    }

    private void reads(String path, Presence presence, String content) {
        when(hostFiles.read(alice, host, path))
                .thenReturn(new HostFileRead(presence, content, false));
    }

    // ------------------------------------------------------- le relevé nominal

    @Test
    @DisplayName("le relevé dit ce que chaque fichier porte, et TOTALISE les faits")
    void thedigestTotalsTheFacts() {
        reads("README.md", Presence.PRESENT, """
                # La carte du poste

                ## Contacts

                | Rôle | Canal | Source et date |
                |---|---|---|
                | Réseau | ticket JIRA | courriel, constaté le 2026-09-01 |
                """);
        reads("acces.md", Presence.PRESENT, "# Accès\n\n## Les pièges\n");

        GovernanceMapView view = service.describe(alice, host);

        assertThat(view.supported()).isTrue();
        assertThat(view.governed()).isTrue();
        assertThat(view.readable()).isTrue();
        assertThat(view.message()).isNull();
        assertThat(view.filesExpected()).isEqualTo(2);
        assertThat(view.filesPresent()).isEqualTo(2);
        assertThat(view.sections()).isEqualTo(2);
        assertThat(view.facts()).isEqualTo(1);
        assertThat(view.files()).extracting("path", "title", "facts").containsExactly(
                org.assertj.core.groups.Tuple.tuple("README.md", "La carte du poste", 1),
                org.assertj.core.groups.Tuple.tuple("acces.md", "Accès", 0));
        // Le gabarit de PROJET n'est pas de la carte : il n'est jamais lu à la racine.
        verify(hostFiles, never()).read(eq(alice), eq(host), eq("STATE.md"));
    }

    @Test
    @DisplayName("un fichier absent le dit, avec le geste qui le repose — et n'arrête pas la lecture")
    void amissingFileNamesItsFix() {
        reads("README.md", Presence.ABSENT, null);
        reads("acces.md", Presence.PRESENT, "# Accès\n");

        GovernanceMapView view = service.describe(alice, host);

        assertThat(view.readable()).isTrue();
        assertThat(view.filesPresent()).isEqualTo(1);
        assertThat(view.files().get(0).present()).isFalse();
        assertThat(view.files().get(0).message()).contains("Appliquer");
        assertThat(view.files().get(1).present()).isTrue();
    }

    @Test
    @DisplayName("un fichier illisible le dit, et le suivant est lu quand même")
    void anUnreadableFileDoesNotStopTheRest() {
        reads("README.md", Presence.UNKNOWN, null);
        reads("acces.md", Presence.PRESENT, "# Accès\n");

        GovernanceMapView view = service.describe(alice, host);

        assertThat(view.readable()).isTrue();
        assertThat(view.files().get(0).readable()).isFalse();
        assertThat(view.files().get(0).message()).contains("droits");
        verify(hostFiles).read(alice, host, "acces.md");
    }

    // ------------------------------------------------------------- les trois « non »

    @Test
    @DisplayName("poste sans machine : aucun appel, et le geste est « connectez une machine »")
    void ahostWithoutAMachineIsNotRead() {
        GovernanceHostRef hosted = GovernanceHostRef.HOSTED;
        when(hostFiles.supports(hosted)).thenReturn(false);
        when(hostScope.nameOf(alice, hosted)).thenReturn("Hébergé");

        GovernanceMapView view = service.describe(alice, hosted);

        assertThat(view.supported()).isFalse();
        assertThat(view.governed()).isFalse();
        assertThat(view.message()).contains("Connectez une machine");
        verify(hostFiles, never()).read(any(), any(), any());
    }

    @Test
    @DisplayName("poste non gouverné : aucun appel, et le geste est « activez le paquet »")
    void anUngovernedHostIsNotRead() {
        when(activations.findByUserIdAndHostIdOrderByCreatedAtAsc(alice, hostId))
                .thenReturn(List.of());

        GovernanceMapView view = service.describe(alice, host);

        assertThat(view.supported()).isTrue();
        assertThat(view.governed()).isFalse();
        assertThat(view.message()).contains("activez").contains("Le savoir durable");
        verify(hostFiles, never()).read(any(), any(), any());
    }

    @Test
    @DisplayName("machine muette : constatée UNE fois, pas six — et le geste est « lancez le runner »")
    void asilentMachineIsProbedOnlyOnce() {
        reads("README.md", Presence.UNREACHABLE, null);

        GovernanceMapView view = service.describe(alice, host);

        assertThat(view.readable()).isFalse();
        assertThat(view.message()).contains("lancez le runner");
        assertThat(view.files()).extracting("readable").containsOnly(false, false);
        verify(hostFiles).read(alice, host, "README.md");
        verify(hostFiles, never()).read(eq(alice), eq(host), eq("acces.md"));
    }

    // ------------------------------------------------------------- le contenu

    @Test
    @DisplayName("le contenu exact d'un fichier de carte est rendu")
    void theExactContentIsReturned() {
        reads("acces.md", Presence.PRESENT, "# Accès\n\n## Les pièges\n");

        GovernanceMapFileContent content = service.readFile(alice, host, "acces.md");

        assertThat(content.present()).isTrue();
        assertThat(content.title()).isEqualTo("Accès");
        assertThat(content.content()).contains("## Les pièges");
        assertThat(content.truncated()).isFalse();
    }

    @Test
    @DisplayName("un chemin hors carte est INTROUVABLE, même s'il existe à la racine")
    void apathOutsideTheMapIsNotFound() {
        for (String path : new String[] {"STATE.md", "secrets.env", "../etc/passwd", ""}) {
            assertThatThrownBy(() -> service.readFile(alice, host, path))
                    .as("chemin %s", path)
                    .isInstanceOf(GovernancePackageNotFoundException.class)
                    .hasMessageContaining("n'appartient pas à la carte");
        }
        verify(hostFiles, never()).read(any(), any(), any());
    }

    @Test
    @DisplayName("un fichier de carte absent rend son geste, jamais un contenu inventé")
    void amissingContentNamesItsFix() {
        reads("acces.md", Presence.ABSENT, null);

        GovernanceMapFileContent content = service.readFile(alice, host, "acces.md");

        assertThat(content.present()).isFalse();
        assertThat(content.content()).isEmpty();
        assertThat(content.message()).contains("Appliquer");
    }
}
