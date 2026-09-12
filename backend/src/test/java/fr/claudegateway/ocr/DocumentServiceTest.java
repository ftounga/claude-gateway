package fr.claudegateway.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import fr.claudegateway.docx.DocxFixtures;
import fr.claudegateway.docx.DocxProperties;
import fr.claudegateway.docx.DocxTextExtractor;
import fr.claudegateway.docx.InvalidDocxException;
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

    /** Le type MIME d'un `.docx`, écrit une fois. */
    private static final String DOCX = OcrProperties.DOCX_MEDIA_TYPE;

    private DocumentService documentService;

    /**
     * Le <b>vrai</b> extracteur, pas un bouchon : il est pur (des octets en entrée, du texte en
     * sortie), et l'utiliser tel quel fait que ces tests éprouvent la chaîne entière — du multipart
     * jusqu'au texte persisté — au lieu d'un contrat simulé.
     */
    private final DocxTextExtractor docxTextExtractor =
            new DocxTextExtractor(new DocxProperties(null, null, null));

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        OcrProperties properties = new OcrProperties(null, null, null, null, null);
        documentService = new DocumentService(documentRepository, ocrProvider, docxTextExtractor, properties);
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

    // ------------------------------------------------------------------ la quatrième voie (F-86)

    @Test
    void wordDocumentIsExtractedOnTheMachineWithoutEverCallingTheOcrProvider() {
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", "contrat.docx", DOCX,
                DocxFixtures.docx(DocxFixtures.paragraph("Le contrat prend effet au 1er janvier.")
                        + DocxFixtures.table(new String[] { "Prestation", "Prix" },
                                new String[] { "Audit", "1 200 €" })));

        Document saved = documentService.submit(alice, file);

        assertThat(saved.getUserId()).isEqualTo(alice);
        assertThat(saved.getOcrMode()).isEqualTo(OcrMode.LOCAL);
        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.EXTRACTED);
        assertThat(saved.getProviderJobId()).isNull();
        assertThat(saved.getTextractRaw()).isNull();
        // La chaîne de SF-86-01 arrive intacte jusqu'au texte persisté : le tableau est en Markdown.
        assertThat(saved.getExtractedText())
                .contains("Le contrat prend effet au 1er janvier.")
                .contains("| Prestation | Prix |")
                .contains("| Audit | 1 200 € |");
        // Le cœur de la décision : un .docx n'est pas une image, il ne va chez aucun OCR.
        verifyNoInteractions(ocrProvider);
    }

    @Test
    void anUnreadableWordDocumentIsRefusedAndNothingIsPersisted() {
        MockMultipartFile file = new MockMultipartFile("file", "contrat.docx", DOCX,
                "Ceci n'est pas une archive.".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> documentService.submit(alice, file))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessageContaining("n'est pas une archive Office");
        // Un échec de REQUÊTE, pas un échec fournisseur : aucune ligne fantôme dans la bibliothèque.
        verify(documentRepository, never()).save(any());
        verifyNoInteractions(ocrProvider);
    }

    @Test
    void aZipRenamedAsDocxIsRefusedOnItsContentNotOnItsName() {
        MockMultipartFile file = new MockMultipartFile("file", "faux-contrat.docx", DOCX,
                DocxFixtures.archive(java.util.Map.of("notes.txt", "rien de Word ici")));

        assertThatThrownBy(() -> documentService.submit(alice, file))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessageContaining("n'est pas un document Word (.docx) valide");
        verify(documentRepository, never()).save(any());
    }

    @Test
    void aZipBombIsRefusedAndNothingIsPersisted() {
        MockMultipartFile file = new MockMultipartFile("file", "bombe.docx", DOCX,
                DocxFixtures.archiveWithManyEntries(5000));

        assertThatThrownBy(() -> documentService.submit(alice, file))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessageContaining("trop volumineux");
        verify(documentRepository, never()).save(any());
    }

    @Test
    void aWordDocumentDeclaredAsOctetStreamIsAcceptedOnItsContent() {
        // Un poste sans suite bureautique n'associe aucun type MIME à `.docx` : refuser ici serait
        // refuser exactement la personne que F-86 existe pour servir.
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", "contrat.docx", "application/octet-stream",
                DocxFixtures.docx(DocxFixtures.paragraph("Reconnu sur son contenu.")));

        Document saved = documentService.submit(alice, file);

        assertThat(saved.getMediaType()).isEqualTo(DOCX);
        assertThat(saved.getOcrMode()).isEqualTo(OcrMode.LOCAL);
        assertThat(saved.getExtractedText()).isEqualTo("Reconnu sur son contenu.");
    }

    @Test
    void aNonDocxDeclaredAsOctetStreamStaysRefused() {
        MockMultipartFile file = new MockMultipartFile("file", "inconnu.bin", "application/octet-stream",
                new byte[] { 1, 2, 3, 4 });

        assertThatThrownBy(() -> documentService.submit(alice, file))
                .isInstanceOf(UnsupportedFileTypeException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void aFalselyDeclaredTypeOutsideTheWhitelistIsNeverSniffed() {
        // Le reniflage ne s'applique QU'AUX types qui ne disent rien. Un type déclaré et faux
        // n'ouvre aucune porte — même si le contenu, lui, est un vrai .docx.
        MockMultipartFile file = new MockMultipartFile("file", "deguise.bmp", "image/bmp",
                DocxFixtures.docx(DocxFixtures.paragraph("Un vrai .docx sous une fausse étiquette.")));

        assertThatThrownBy(() -> documentService.submit(alice, file))
                .isInstanceOf(UnsupportedFileTypeException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void rejectsTooLargeFile() {
        OcrProperties tiny = new OcrProperties("stub", DataSize.ofBytes(2), null, null, null);
        DocumentService service = new DocumentService(documentRepository, ocrProvider, docxTextExtractor, tiny);
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
