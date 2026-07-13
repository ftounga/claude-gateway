package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.WorkspaceService.CreatedWorkspace;
import fr.claudegateway.atelier.storage.InMemoryWorkspaceStorage;
import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * Tests unitaires du cœur Atelier : décompression sécurisée (zip-slip, zip-bomb), initialisation de
 * CLAUDE.md, isolation et path-safety. Stockage en mémoire (aucun réseau).
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    private final WorkspaceStorage storage = new InMemoryWorkspaceStorage();
    private final UUID alice = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private WorkspaceService serviceWith(long maxTotal, int maxEntries, long maxFile) {
        return new WorkspaceService(workspaceRepository, storage,
                new AtelierProperties("in-memory", null, "atelier/", maxTotal, maxEntries, maxFile));
    }

    private WorkspaceService service() {
        return serviceWith(50L * 1024 * 1024, 2000, 2L * 1024 * 1024);
    }

    private void givenSaveAssignsId() {
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(invocation -> {
            Workspace w = invocation.getArgument(0);
            w.setId(workspaceId);
            return w;
        });
    }

    private void givenOwned() {
        when(workspaceRepository.findByIdAndUserId(workspaceId, alice))
                .thenReturn(Optional.of(Workspace.builder().id(workspaceId).userId(alice).name("p").build()));
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void extractsFilesAndInitialisesClaudeMdWhenAbsent() throws IOException {
        givenSaveAssignsId();
        byte[] archive = zip(Map.of("src/App.java", "class App {}", "README.md", "hi"));

        CreatedWorkspace created = service().create(alice, "Projet", archive);

        // 2 fichiers + CLAUDE.md initialisé.
        assertThat(created.fileCount()).isEqualTo(3);
        givenOwned();
        assertThat(service().tree(alice, workspaceId))
                .contains("src/App.java", "README.md", "CLAUDE.md");
    }

    @Test
    void keepsProvidedClaudeMd() throws IOException {
        givenSaveAssignsId();
        byte[] archive = zip(Map.of("CLAUDE.md", "# Mes conventions", "a.txt", "x"));

        service().create(alice, "P", archive);

        givenOwned();
        assertThat(service().readFile(alice, workspaceId, "CLAUDE.md")).isEqualTo("# Mes conventions");
    }

    @Test
    void ignoresZipSlipEntries() throws IOException {
        givenSaveAssignsId();
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("../evil.txt", "pwned");
        entries.put("/abs.txt", "pwned");
        entries.put("ok.txt", "safe");
        byte[] archive = zip(entries);

        CreatedWorkspace created = service().create(alice, "P", archive);

        givenOwned();
        // Seuls ok.txt + CLAUDE.md sont retenus ; aucune clé ne contient « evil » ni « .. ».
        assertThat(created.fileCount()).isEqualTo(2);
        assertThat(service().tree(alice, workspaceId)).containsExactlyInAnyOrder("ok.txt", "CLAUDE.md");
    }

    @Test
    void rejectsArchiveWithTooManyEntries() throws IOException {
        byte[] archive = zip(Map.of("a", "1", "b", "2", "c", "3"));

        assertThatThrownBy(() -> serviceWith(50_000_000, 2, 2_000_000).create(alice, "P", archive))
                .isInstanceOf(InvalidArchiveException.class);
    }

    @Test
    void rejectsArchiveWithOversizedFile() throws IOException {
        byte[] archive = zip(Map.of("big.txt", "0123456789")); // 10 octets

        assertThatThrownBy(() -> serviceWith(50_000_000, 2000, 5).create(alice, "P", archive))
                .isInstanceOf(InvalidArchiveException.class);
    }

    @Test
    void rejectsArchiveExceedingTotalSize() throws IOException {
        byte[] archive = zip(Map.of("a.txt", "01234", "b.txt", "56789")); // 10 octets au total

        assertThatThrownBy(() -> serviceWith(8, 2000, 2_000_000).create(alice, "P", archive))
                .isInstanceOf(InvalidArchiveException.class);
    }

    @Test
    void readFileRejectsPathTraversal() {
        givenOwned();
        assertThatThrownBy(() -> service().readFile(alice, workspaceId, "../../etc/passwd"))
                .isInstanceOf(InvalidFilePathException.class);
    }

    @Test
    void requireOwnedRejectsWorkspaceOfAnotherUser() {
        when(workspaceRepository.findByIdAndUserId(workspaceId, alice)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().requireOwned(alice, workspaceId))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void deleteFileRemovesItFromTree() throws IOException {
        givenSaveAssignsId();
        service().create(alice, "P", zip(Map.of("a.txt", "x", "keep.txt", "y")));
        givenOwned();

        service().deleteFile(alice, workspaceId, "a.txt");

        assertThat(service().tree(alice, workspaceId)).doesNotContain("a.txt").contains("keep.txt");
    }

    @Test
    void deleteFileRejectsUnknownFile() throws IOException {
        givenSaveAssignsId();
        service().create(alice, "P", zip(Map.of("a.txt", "x")));
        givenOwned();

        assertThatThrownBy(() -> service().deleteFile(alice, workspaceId, "nope.txt"))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void deleteFileRejectsWorkspaceOfAnotherUser() {
        when(workspaceRepository.findByIdAndUserId(workspaceId, alice)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().deleteFile(alice, workspaceId, "a.txt"))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void renameFileMovesContentAndDropsOriginal() throws IOException {
        givenSaveAssignsId();
        service().create(alice, "P", zip(Map.of("a.txt", "hello")));
        givenOwned();

        service().renameFile(alice, workspaceId, "a.txt", "sub/b.txt");

        assertThat(service().readFile(alice, workspaceId, "sub/b.txt")).isEqualTo("hello");
        assertThat(service().tree(alice, workspaceId)).contains("sub/b.txt").doesNotContain("a.txt");
        assertThatThrownBy(() -> service().readFile(alice, workspaceId, "a.txt"))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void renameFileRejectsInvalidDestination() throws IOException {
        givenSaveAssignsId();
        service().create(alice, "P", zip(Map.of("a.txt", "hello")));
        givenOwned();

        assertThatThrownBy(() -> service().renameFile(alice, workspaceId, "a.txt", "../x"))
                .isInstanceOf(InvalidFilePathException.class);
        // La source reste intacte après un renommage refusé.
        assertThat(service().readFile(alice, workspaceId, "a.txt")).isEqualTo("hello");
    }

    @Test
    void renameFileRejectsUnknownSource() throws IOException {
        givenSaveAssignsId();
        service().create(alice, "P", zip(Map.of("a.txt", "hello")));
        givenOwned();

        assertThatThrownBy(() -> service().renameFile(alice, workspaceId, "ghost.txt", "b.txt"))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void exportZipRoundTripsRelativePaths() throws IOException {
        givenSaveAssignsId();
        service().create(alice, "P", zip(Map.of("src/App.java", "class App {}", "README.md", "hi")));
        givenOwned();

        byte[] archive = service().exportZip(alice, workspaceId);

        assertThat(archive).isNotEmpty();
        Map<String, String> unzipped = unzip(archive);
        // Round-trip : mêmes chemins relatifs (sans préfixe de stockage), CLAUDE.md inclus.
        assertThat(unzipped).containsKeys("src/App.java", "README.md", "CLAUDE.md");
        assertThat(unzipped.get("src/App.java")).isEqualTo("class App {}");
    }

    private static Map<String, String> unzip(byte[] archive) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zis =
                new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                out.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                zis.closeEntry();
            }
        }
        return out;
    }
}
