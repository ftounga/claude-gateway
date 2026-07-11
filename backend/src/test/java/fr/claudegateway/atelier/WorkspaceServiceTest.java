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
}
