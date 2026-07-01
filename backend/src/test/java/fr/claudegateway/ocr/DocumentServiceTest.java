package fr.claudegateway.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import fr.claudegateway.ocr.provider.OcrDocument;
import fr.claudegateway.ocr.provider.OcrExtraction;
import fr.claudegateway.ocr.provider.OcrProvider;
import fr.claudegateway.ocr.provider.OcrProviderException;
import fr.claudegateway.upload.EmptyFileException;
import fr.claudegateway.upload.FileTooLargeException;
import fr.claudegateway.upload.UnsupportedFileTypeException;

/**
 * Tests unitaires du cœur OCR F-05 : validation, routage sync/async, délégation au
 * {@link OcrProvider} (mocké — jamais d'appel AWS), persistance de l'état et isolation {@code user_id}.
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private OcrProvider ocrProvider;

    private DocumentService documentService;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        OcrProperties properties = new OcrProperties(null, null, null, null);
        documentService = new DocumentService(documentRepository, ocrProvider, properties);
    }

    @Test
    void imageIsExtractedSynchronouslyAndPersisted() {
        when(ocrProvider.extractSync(any(OcrDocument.class)))
                .thenReturn(new OcrExtraction("Bonjour le monde", "{\"provider\":\"stub\"}"));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.png", "image/png", new byte[] {1, 2, 3, 4});

        Document saved = documentService.submit(alice, file);

        assertThat(saved.getUserId()).isEqualTo(alice);
        assertThat(saved.getOcrMode()).isEqualTo(OcrMode.SYNC);
        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.EXTRACTED);
        assertThat(saved.getExtractedText()).isEqualTo("Bonjour le monde");
        assertThat(saved.getTextractRaw()).contains("provider");
        assertThat(saved.getProviderJobId()).isNull();
        verify(ocrProvider, never()).startAsync(any());
    }

    @Test
    void pdfIsSubmittedAsynchronouslyAndLeftProcessing() {
        when(ocrProvider.startAsync(any(OcrDocument.class))).thenReturn("job-42");
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "contrat.pdf", "application/pdf", new byte[] {5, 6, 7});

        Document saved = documentService.submit(alice, file);

        assertThat(saved.getOcrMode()).isEqualTo(OcrMode.ASYNC);
        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(saved.getProviderJobId()).isEqualTo("job-42");
        assertThat(saved.getExtractedText()).isNull();
        verify(ocrProvider, never()).extractSync(any());
    }

    @Test
    void providerFailureOnImageMarksDocumentFailedWithNeutralMessage() {
        when(ocrProvider.extractSync(any(OcrDocument.class)))
                .thenThrow(new OcrProviderException("détail brut interne à ne pas exposer"));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.jpg", "image/jpeg", new byte[] {1});

        Document saved = documentService.submit(alice, file);

        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(saved.getErrorMessage()).isEqualTo("Échec de l'extraction OCR.");
        assertThat(saved.getExtractedText()).isNull();
    }

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "vide.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> documentService.submit(alice, file))
                .isInstanceOf(EmptyFileException.class);
        verify(ocrProvider, never()).extractSync(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void rejectsUnsupportedType() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.exe", "application/x-msdownload", new byte[] {1, 2});

        assertThatThrownBy(() -> documentService.submit(alice, file))
                .isInstanceOf(UnsupportedFileTypeException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void rejectsTooLargeFile() {
        OcrProperties tiny = new OcrProperties("stub", DataSize.ofBytes(2), null, null);
        DocumentService service = new DocumentService(documentRepository, ocrProvider, tiny);
        MockMultipartFile file = new MockMultipartFile(
                "file", "gros.png", "image/png", new byte[] {1, 2, 3, 4});

        assertThatThrownBy(() -> service.submit(alice, file))
                .isInstanceOf(FileTooLargeException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void getByIdEnforcesUserIsolation() {
        UUID documentId = UUID.randomUUID();
        // Le document appartient à Alice : la requête de Bob ne le trouve pas.
        when(documentRepository.findByIdAndUserId(documentId, bob)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.getById(bob, documentId))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void deleteRemovesOwnedDocument() {
        UUID documentId = UUID.randomUUID();
        Document document = Document.builder()
                .userId(alice).filename("scan.png").mediaType("image/png").sizeBytes(4)
                .status(DocumentStatus.EXTRACTED).ocrMode(OcrMode.SYNC).build();
        when(documentRepository.findByIdAndUserId(documentId, alice))
                .thenReturn(Optional.of(document));

        documentService.delete(alice, documentId);

        verify(documentRepository).delete(document);
    }

    @Test
    void deleteEnforcesUserIsolation() {
        UUID documentId = UUID.randomUUID();
        // Le document appartient à Alice : la suppression demandée par Bob ne le trouve pas.
        when(documentRepository.findByIdAndUserId(documentId, bob)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.delete(bob, documentId))
                .isInstanceOf(DocumentNotFoundException.class);
        verify(documentRepository, never()).delete(any(Document.class));
    }
}
