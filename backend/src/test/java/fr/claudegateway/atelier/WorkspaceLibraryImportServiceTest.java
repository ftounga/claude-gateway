package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.storage.InMemoryWorkspaceStorage;
import fr.claudegateway.atelier.storage.WorkspaceStorage;
import fr.claudegateway.chat.DocumentNotReadyException;
import fr.claudegateway.ocr.Document;
import fr.claudegateway.ocr.DocumentNotFoundException;
import fr.claudegateway.ocr.DocumentRepository;

/**
 * Tests unitaires de l'import de documents de bibliothèque dans un workspace (SF-28-13) : nominal
 * (texte écrit sous {@code bibliotheque/…}, arborescence renvoyée) et isolation (workspace non
 * possédé → 404, document d'un autre user → 404, document sans texte → 409). Stockage en mémoire.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceLibraryImportServiceTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private DocumentRepository documentRepository;

    private final WorkspaceStorage storage = new InMemoryWorkspaceStorage();
    private final UUID alice = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private WorkspaceLibraryImportService service;

    @BeforeEach
    void setUp() {
        WorkspaceService workspaceService = new WorkspaceService(workspaceRepository, storage,
                new AtelierProperties("in-memory", null, "atelier/", 50L * 1024 * 1024, 2000,
                        2L * 1024 * 1024));
        service = new WorkspaceLibraryImportService(workspaceService, documentRepository);
    }

    private void givenOwnedWorkspace() {
        lenient().when(workspaceRepository.findByIdAndUserId(workspaceId, alice))
                .thenReturn(Optional.of(
                        Workspace.builder().id(workspaceId).userId(alice).name("p").build()));
        lenient().when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Document doc(String filename, String text) {
        return Document.builder().id(UUID.randomUUID()).userId(alice)
                .filename(filename).extractedText(text).build();
    }

    @Test
    void importsDocumentTextUnderLibraryFolderAndReturnsTree() {
        givenOwnedWorkspace();
        Document document = doc("Mon Contrat.pdf", "Texte OCR du contrat.");
        when(documentRepository.findByIdAndUserId(document.getId(), alice))
                .thenReturn(Optional.of(document));

        List<String> tree = service.importDocuments(alice, workspaceId, List.of(document.getId()));

        assertThat(tree).contains("bibliotheque/Mon_Contrat.pdf.md");
        byte[] written = storage.getFile("atelier/" + alice + "/" + workspaceId
                + "/bibliotheque/Mon_Contrat.pdf.md").orElseThrow();
        assertThat(new String(written, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("Texte OCR du contrat.");
    }

    @Test
    void rejectsWorkspaceOfAnotherUserWith404() {
        when(workspaceRepository.findByIdAndUserId(workspaceId, alice)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.importDocuments(alice, workspaceId, List.of(UUID.randomUUID())))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void rejectsDocumentOfAnotherUserWith404() {
        givenOwnedWorkspace();
        UUID foreignDoc = UUID.randomUUID();
        when(documentRepository.findByIdAndUserId(foreignDoc, alice)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.importDocuments(alice, workspaceId, List.of(foreignDoc)))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void rejectsDocumentWithoutExtractedTextWith409() {
        givenOwnedWorkspace();
        Document document = doc("En cours.pdf", "   ");
        when(documentRepository.findByIdAndUserId(document.getId(), alice))
                .thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.importDocuments(alice, workspaceId, List.of(document.getId())))
                .isInstanceOf(DocumentNotReadyException.class);
    }
}
