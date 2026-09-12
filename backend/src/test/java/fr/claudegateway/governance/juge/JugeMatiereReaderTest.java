package fr.claudegateway.governance.juge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
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

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.governance.GovernanceFileKind;
import fr.claudegateway.governance.GovernanceHostFiles;
import fr.claudegateway.governance.GovernanceHostFiles.HostFileRead;
import fr.claudegateway.governance.GovernanceHostFiles.Presence;
import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.GovernanceHostScope;
import fr.claudegateway.governance.GovernanceMapDestinations;
import fr.claudegateway.governance.GovernancePackageFile;
import fr.claudegateway.governance.GovernanceProjectFiles;

/**
 * F-94 / SF-94-01 — <b>la matière du juge</b>.
 *
 * <p>Ce que ces tests protègent :</p>
 * <ul>
 *   <li>les notes sont les {@code .md} de la <b>racine</b> d'un projet — un sous-dossier est du
 *       bruit, et le bruit fait crier le filet à chaque tour ;</li>
 *   <li>un <b>gabarit jamais touché</b> n'est pas une note : ses exemples feraient alerter le juge
 *       dès le premier projet ;</li>
 *   <li>une carte <b>injoignable</b> ne donne pas une carte vide — sinon tout serait « absent » ;</li>
 *   <li>rien ne lève, et le poste « Hébergé » n'appelle <b>aucune</b> machine.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JugeMatiereReaderTest {

    @Mock
    private GovernanceMapDestinations destinations;
    @Mock
    private GovernanceHostFiles hostFiles;
    @Mock
    private GovernanceHostScope hostScope;
    @Mock
    private GovernanceProjectFiles projectFiles;

    private JugeMatiereReader reader;

    private final UUID alice = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(hostId);
    private Workspace projet;

    private static final String GABARIT = "# État du sujet\n\n- [ ] cluster « atlas » (10.0.4.0/24)\n";

    @BeforeEach
    void setUp() {
        reader = new JugeMatiereReader(destinations, hostFiles, hostScope, projectFiles);
        projet = Workspace.builder().id(UUID.randomUUID()).userId(alice).hostId(hostId)
                .name("Migration DNS").projectPath("migration-dns").build();

        when(hostFiles.supports(host)).thenReturn(true);
        when(destinations.filesOf(alice, host))
                .thenReturn(Map.of("acces.md", file("acces.md", GovernanceFileKind.MAP)));
        when(destinations.projectFilesOf(alice, host))
                .thenReturn(Map.of("STATE.md", file("STATE.md", GovernanceFileKind.TEMPLATE)));
        when(hostFiles.read(alice, host, "acces.md"))
                .thenReturn(new HostFileRead(Presence.PRESENT, "# Accès\n\nVPN : aucun.\n", false));
        when(hostScope.projectsOf(alice, host)).thenReturn(List.of(projet));
    }

    private GovernancePackageFile file(String path, GovernanceFileKind kind) {
        return GovernancePackageFile.builder().id(UUID.randomUUID()).path(path).kind(kind)
                .content(GABARIT).build();
    }

    @Test
    @DisplayName("Seuls les .md de la racine du projet deviennent des notes")
    void seulementLaRacine() {
        when(projectFiles.listPathsOrEmpty(alice, projet)).thenReturn(List.of(
                "NOTES.md", "sources/export.md", "context/brief.md", "script.sh",
                ".claude/skills/explique.md"));
        when(projectFiles.read(alice, projet, "NOTES.md"))
                .thenReturn(Optional.of("Le bastion bst-01 dessert la prod."));

        JugeMatiere matiere = reader.lire(alice, host);

        assertThat(matiere.utilisable()).isTrue();
        assertThat(matiere.notes()).extracting(JugeMatiere.Piece::chemin)
                .containsExactly("migration-dns/NOTES.md");
        verify(projectFiles, never()).read(eq(alice), any(), eq("sources/export.md"));
    }

    @Test
    @DisplayName("Un gabarit jamais touché n'est pas une note ; enrichi, il en devient une")
    void gabaritIntactEcarte() {
        when(projectFiles.listPathsOrEmpty(alice, projet)).thenReturn(List.of("STATE.md"));
        when(projectFiles.read(alice, projet, "STATE.md")).thenReturn(Optional.of(GABARIT));

        assertThat(reader.lire(alice, host).utilisable()).isFalse();

        when(projectFiles.read(alice, projet, "STATE.md"))
                .thenReturn(Optional.of(GABARIT + "\n- [ ] bastion bst-01\n"));

        JugeMatiere enrichie = reader.lire(alice, host);
        assertThat(enrichie.utilisable()).isTrue();
        assertThat(enrichie.notes()).hasSize(1);
    }

    @Test
    @DisplayName("Carte injoignable : matière inutilisable, jamais une carte vide")
    void carteInjoignable() {
        when(hostFiles.read(alice, host, "acces.md"))
                .thenReturn(new HostFileRead(Presence.UNREACHABLE, null, false));
        when(projectFiles.listPathsOrEmpty(alice, projet)).thenReturn(List.of("NOTES.md"));

        JugeMatiere matiere = reader.lire(alice, host);

        assertThat(matiere.utilisable()).isFalse();
        assertThat(matiere.carte()).isEmpty();
        verify(projectFiles, never()).read(any(), any(), any());
    }

    @Test
    @DisplayName("Poste « Hébergé » : aucune lecture, aucune matière")
    void posteHeberge() {
        when(hostFiles.supports(GovernanceHostRef.HOSTED)).thenReturn(false);

        assertThat(reader.lire(alice, GovernanceHostRef.HOSTED).utilisable()).isFalse();
        verify(hostFiles, never()).read(any(), any(), any());
        verify(hostScope, never()).projectsOf(any(), any());
    }

    @Test
    @DisplayName("Aucun paquet actif : aucune matière, aucun appel machine")
    void aucunPaquetActif() {
        when(destinations.filesOf(alice, host)).thenReturn(Map.of());

        assertThat(reader.lire(alice, host).utilisable()).isFalse();
        verify(hostFiles, never()).read(any(), any(), any());
    }

    @Test
    @DisplayName("Aucune note : aucune matière — il n'y a rien à comparer")
    void aucuneNote() {
        when(projectFiles.listPathsOrEmpty(alice, projet)).thenReturn(List.of("script.sh"));

        assertThat(reader.lire(alice, host).utilisable()).isFalse();
    }

    @Test
    @DisplayName("Rien ne lève : poste dont les projets sont illisibles")
    void projetsIllisibles() {
        doThrow(new IllegalStateException("effacé")).when(hostScope).projectsOf(alice, host);

        assertThat(reader.lire(alice, host).utilisable()).isFalse();
    }

    @Test
    @DisplayName("Rien ne lève : note dont la lecture échoue")
    void lectureDeNoteEnEchec() {
        when(projectFiles.listPathsOrEmpty(alice, projet)).thenReturn(List.of("NOTES.md"));
        doThrow(new IllegalStateException("runner muet")).when(projectFiles)
                .read(alice, projet, "NOTES.md");

        assertThat(reader.lire(alice, host).utilisable()).isFalse();
    }

    @Test
    @DisplayName("Le volume est borné, et la coupe se dit")
    void volumeBorne() {
        when(projectFiles.listPathsOrEmpty(alice, projet)).thenReturn(List.of("NOTES.md"));
        when(projectFiles.read(alice, projet, "NOTES.md"))
                .thenReturn(Optional.of("x".repeat(JugeMatiere.MAX_CHARS_PAR_FICHIER + 5_000)));

        JugeMatiere matiere = reader.lire(alice, host);

        assertThat(matiere.tronquee()).isTrue();
        assertThat(matiere.notes().get(0).contenu())
                .hasSize(JugeMatiere.MAX_CHARS_PAR_FICHIER);
    }

    @Test
    @DisplayName("Isolation : les projets viennent du poste possédé, et d'aucune autre source")
    void isolation() {
        when(projectFiles.listPathsOrEmpty(alice, projet)).thenReturn(List.of("NOTES.md"));
        when(projectFiles.read(alice, projet, "NOTES.md")).thenReturn(Optional.of("un fait"));

        reader.lire(alice, host);

        verify(hostScope).projectsOf(alice, host);
        verify(projectFiles).listPathsOrEmpty(alice, projet);
    }
}
