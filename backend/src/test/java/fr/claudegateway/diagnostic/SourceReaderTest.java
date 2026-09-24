package fr.claudegateway.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.ProjectFileRead;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;

/**
 * Lire le dépôt de l'application (F-157 / SF-157-02).
 *
 * <p>Ce que ces tests tiennent : un projet <b>qui n'est pas le dépôt</b> est refusé — lire un code
 * sans rapport ferait conclure n'importe quoi <b>avec assurance</b> —, seuls les chemins <b>de la
 * carte</b> sont demandés, et un fichier illisible <b>n'interrompt rien</b>.</p>
 */
class SourceReaderTest {

    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final ProjectFileRead files = mock(ProjectFileRead.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private Workspace workspace;
    private SourceReader reader;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(workspaceId);
        when(workspaces.requireOwned(userId, workspaceId)).thenReturn(workspace);
        reader = new SourceReader(workspaces, files);
    }

    /** Toutes les lectures répondent le même contenu. */
    private void everythingReads(String content) {
        when(files.read(eq(userId), eq(workspace), anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(i -> ProjectFileRead.Read.ok(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** Rien ne répond : le projet n'a aucun des fichiers attendus. */
    private void nothingReads() {
        when(files.read(eq(userId), eq(workspace), anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(i -> ProjectFileRead.Read.error("introuvable"));
    }

    @Test
    @DisplayName("un projet qui n'est PAS le dépôt est refusé, et le refus dit pourquoi")
    void aForeignProjectIsRefused() {
        nothingReads();

        assertThatThrownBy(() -> reader.read(userId, workspaceId))
                .isInstanceOf(RepositoryNotRecognizedException.class)
                .hasMessageContaining("n'est pas le dépôt de l'application")
                .hasMessageContaining("Désignez le terminal ouvert sur le dépôt");
    }

    @Test
    @DisplayName("la MAJORITÉ suffit : une branche qui a déplacé un fichier ne bloque pas le diagnostic")
    void theMajorityIsEnough() {
        List<String> paths = SourceReader.declaredPaths();
        // Un seul fichier manque : on doit accepter.
        String missing = paths.get(0);
        when(files.read(eq(userId), eq(workspace), anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(i -> missing.equals(i.getArgument(3))
                        ? ProjectFileRead.Read.error("déplacé")
                        : ProjectFileRead.Read.ok("du code".getBytes(StandardCharsets.UTF_8)));

        Map<String, SourceRead> reads = reader.read(userId, workspaceId);

        assertThat(reads.get(missing).isRead()).isFalse();
        assertThat(reads.get(missing).missing()).isEqualTo("déplacé");
        assertThat(reads.values().stream().filter(SourceRead::isRead).count())
                .isEqualTo(paths.size() - 1L);
    }

    @Test
    @DisplayName("seuls les chemins DE LA CARTE sont demandés — jamais un chemin venu d'ailleurs")
    void onlyDeclaredPathsAreRequested() {
        everythingReads("du code");

        reader.read(userId, workspaceId);

        List<String> declared = SourceReader.declaredPaths();
        org.mockito.ArgumentCaptor<String> asked = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(files, org.mockito.Mockito.atLeastOnce())
                .read(eq(userId), eq(workspace), anyString(), asked.capture(), anyLong(), anyString());
        assertThat(asked.getAllValues()).isSubsetOf(declared);
    }

    @Test
    @DisplayName("le contenu lu est rendu tel quel, et le fragment s'y cherche")
    void contentIsReturnedAndSearchable() {
        everythingReads("void main() { planTouched.set(true); }");

        Map<String, SourceRead> reads = reader.read(userId, workspaceId);
        SourceRead any = reads.values().iterator().next();

        assertThat(any.isRead()).isTrue();
        assertThat(any.contains("planTouched.set(true)")).isTrue();
        assertThat(any.contains("absent-du-fichier")).isFalse();
    }

    @Test
    @DisplayName("un fichier ABSENT ne prouve rien — on ne cherche pas un fragment dans du vide")
    void anAbsentFileProvesNothing() {
        SourceRead absent = SourceRead.absent("x", "illisible");
        assertThat(absent.contains("n'importe quoi")).isFalse();
        assertThat(absent.isRead()).isFalse();
    }

    @Test
    @DisplayName("une exception de lecture n'interrompt rien : le fichier est noté absent")
    void anExceptionDoesNotStopTheReading() {
        List<String> paths = SourceReader.declaredPaths();
        String boom = paths.get(0);
        when(files.read(eq(userId), eq(workspace), anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(i -> {
                    if (boom.equals(i.getArgument(3))) {
                        throw new IllegalStateException("runner injoignable");
                    }
                    return ProjectFileRead.Read.ok("du code".getBytes(StandardCharsets.UTF_8));
                });

        Map<String, SourceRead> reads = reader.read(userId, workspaceId);

        assertThat(reads.get(boom).missing()).contains("Lecture impossible");
        assertThat(reads).hasSameSizeAs(paths);
    }

    @Test
    @DisplayName("la lecture est bornée : plafond d'octets passé au lecteur, plafond de fichiers tenu")
    void theReadingIsBounded() {
        everythingReads("du code");

        reader.read(userId, workspaceId);

        verify(files, org.mockito.Mockito.atLeastOnce()).read(eq(userId), eq(workspace), anyString(),
                anyString(), eq(SourceReader.MAX_BYTES), anyString());
        assertThat(SourceReader.declaredPaths().size())
                .as("la carte reste sous le plafond de fichiers")
                .isLessThanOrEqualTo(SourceReader.MAX_FILES);
    }

    @Test
    @DisplayName("ISOLATION — le projet d'un autre compte est introuvable, et RIEN n'est lu")
    void anotherAccountReadsNothing() {
        UUID intruder = UUID.randomUUID();
        when(workspaces.requireOwned(intruder, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable"));

        assertThatThrownBy(() -> reader.read(intruder, workspaceId))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verify(files, never()).read(any(), any(), anyString(), anyString(), anyLong(), anyString());
    }
}
