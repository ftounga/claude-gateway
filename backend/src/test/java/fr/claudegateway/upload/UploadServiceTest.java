package fr.claudegateway.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ProviderFileReference;
import fr.claudegateway.ai.ProviderFileUpload;
import fr.claudegateway.docx.DocxFixtures;
import fr.claudegateway.docx.DocxProperties;
import fr.claudegateway.docx.DocxTextExtractor;
import fr.claudegateway.docx.InvalidDocxException;
import fr.claudegateway.ocr.OcrProperties;

/**
 * Tests unitaires du cœur de l'upload F-04 : validation (présence, type, taille), transmission via
 * {@link AIProvider} et persistance des métadonnées portant le {@code user_id}.
 */
@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    @Mock
    private AIProvider aiProvider;

    @Mock
    private UploadedFileRepository uploadedFileRepository;

    /** Le vrai extracteur : pur, donc ces tests éprouvent la chaîne entière (voir SF-86-01). */
    private final DocxTextExtractor docxTextExtractor =
            new DocxTextExtractor(new DocxProperties(null, null, null));

    private UploadService uploadService;

    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        UploadProperties properties = new UploadProperties(null, DataSize.ofMegabytes(32));
        uploadService = new UploadService(aiProvider, uploadedFileRepository, docxTextExtractor, properties);
    }

    @Test
    void transmitsToProviderAndPersistsMetadata() {
        when(aiProvider.uploadFile(any())).thenReturn(new ProviderFileReference("file_123"));
        when(uploadedFileRepository.save(any(UploadedFile.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "rapport.pdf", "application/pdf", new byte[] {1, 2, 3, 4});

        UploadedFile saved = uploadService.upload(alice, file);

        ArgumentCaptor<ProviderFileUpload> uploadCaptor = ArgumentCaptor.forClass(ProviderFileUpload.class);
        verify(aiProvider).uploadFile(uploadCaptor.capture());
        assertThat(uploadCaptor.getValue().filename()).isEqualTo("rapport.pdf");
        assertThat(uploadCaptor.getValue().mediaType()).isEqualTo("application/pdf");

        assertThat(saved.getUserId()).isEqualTo(alice);
        assertThat(saved.getProviderFileId()).isEqualTo("file_123");
        assertThat(saved.getMediaType()).isEqualTo("application/pdf");
        assertThat(saved.getSizeBytes()).isEqualTo(4);
    }

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "vide.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> uploadService.upload(alice, file))
                .isInstanceOf(EmptyFileException.class);
        verify(aiProvider, never()).uploadFile(any());
    }

    @Test
    void rejectsUnsupportedType() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "malware.exe", "application/x-msdownload", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> uploadService.upload(alice, file))
                .isInstanceOf(UnsupportedFileTypeException.class);
        verify(aiProvider, never()).uploadFile(any());
    }

    @Test
    void rejectsTooLargeFile() {
        UploadProperties tiny = new UploadProperties(null, DataSize.ofBytes(2));
        UploadService service = new UploadService(aiProvider, uploadedFileRepository, docxTextExtractor, tiny);
        MockMultipartFile file = new MockMultipartFile(
                "file", "gros.pdf", "application/pdf", new byte[] {1, 2, 3, 4});

        assertThatThrownBy(() -> service.upload(alice, file))
                .isInstanceOf(FileTooLargeException.class);
        verify(aiProvider, never()).uploadFile(any());
    }

    @Test
    void normalizesContentTypeWithCharsetParameter() {
        when(aiProvider.uploadFile(any())).thenReturn(new ProviderFileReference("file_txt"));
        when(uploadedFileRepository.save(any(UploadedFile.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain; charset=utf-8", new byte[] {9});

        UploadedFile saved = uploadService.upload(alice, file);

        assertThat(saved.getMediaType()).isEqualTo("text/plain");
    }

    // ------------------------------------------------------------ pièce jointe Word (F-86)

    private static final String DOCX = OcrProperties.DOCX_MEDIA_TYPE;

    private static byte[] wordContract() {
        return DocxFixtures.docx(DocxFixtures.paragraph("Le contrat prend effet au 1er janvier.")
                + DocxFixtures.table(new String[] { "Prestation", "Prix" },
                        new String[] { "Audit", "1 200 €" }));
    }

    @Test
    void aWordAttachmentIsTransmittedAsTextBecauseTheProviderCannotReadDocx() {
        when(aiProvider.uploadFile(any())).thenReturn(new ProviderFileReference("file_docx"));
        when(uploadedFileRepository.save(any(UploadedFile.class))).thenAnswer(inv -> inv.getArgument(0));
        byte[] original = wordContract();
        MockMultipartFile file = new MockMultipartFile("file", "contrat.docx", DOCX, original);

        UploadedFile saved = uploadService.upload(alice, file);

        ArgumentCaptor<ProviderFileUpload> captor = ArgumentCaptor.forClass(ProviderFileUpload.class);
        verify(aiProvider).uploadFile(captor.capture());
        ProviderFileUpload transmitted = captor.getValue();

        // Ce qui part est du TEXTE, pas le zip : un bloc `document` ne sait pas lire un .docx.
        assertThat(transmitted.mediaType()).isEqualTo("text/plain");
        assertThat(transmitted.filename()).isEqualTo("contrat.docx.txt");
        assertThat(transmitted.content()).isNotEqualTo(original);
        String sent = new String(transmitted.content(), StandardCharsets.UTF_8);
        assertThat(sent)
                .contains("Le contrat prend effet au 1er janvier.")
                .contains("| Prestation | Prix |")
                .contains("| Audit | 1 200 € |");
        // Aucun octet du zip ne voyage : « PK » est la signature d'une archive.
        assertThat(sent).doesNotContain("PK");

        // Les métadonnées décrivent l'attachement de L'UTILISATEUR, pas la copie du fournisseur :
        // afficher « TXT » sous un document joint en Word serait exact et faux à la fois.
        assertThat(saved.getUserId()).isEqualTo(alice);
        assertThat(saved.getFilename()).isEqualTo("contrat.docx");
        assertThat(saved.getMediaType()).isEqualTo(DOCX);
        assertThat(saved.getSizeBytes()).isEqualTo(original.length);
        assertThat(saved.getProviderFileId()).isEqualTo("file_docx");
    }

    @Test
    void anUnreadableWordAttachmentNeverReachesTheProvider() {
        MockMultipartFile file = new MockMultipartFile("file", "contrat.docx", DOCX,
                "Ceci n'est pas une archive.".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> uploadService.upload(alice, file))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessageContaining("n'est pas une archive Office");

        verifyNoInteractions(aiProvider);
        verifyNoInteractions(uploadedFileRepository);
    }

    @Test
    void aZipBombNeverReachesTheProvider() {
        MockMultipartFile file = new MockMultipartFile("file", "bombe.docx", DOCX,
                DocxFixtures.archiveWithManyEntries(5000));

        assertThatThrownBy(() -> uploadService.upload(alice, file))
                .isInstanceOf(InvalidDocxException.class)
                .hasMessageContaining("trop volumineux");

        verifyNoInteractions(aiProvider);
        verifyNoInteractions(uploadedFileRepository);
    }

    @Test
    void aWordAttachmentDeclaredAsOctetStreamIsAcceptedOnItsContent() {
        when(aiProvider.uploadFile(any())).thenReturn(new ProviderFileReference("file_sniffed"));
        when(uploadedFileRepository.save(any(UploadedFile.class))).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile file = new MockMultipartFile(
                "file", "contrat.docx", "application/octet-stream", wordContract());

        UploadedFile saved = uploadService.upload(alice, file);

        assertThat(saved.getMediaType()).isEqualTo(DOCX);
    }

    @Test
    void aFalselyDeclaredTypeIsNeverSniffed() {
        MockMultipartFile file = new MockMultipartFile("file", "deguise.bmp", "image/bmp", wordContract());

        assertThatThrownBy(() -> uploadService.upload(alice, file))
                .isInstanceOf(UnsupportedFileTypeException.class);
        verifyNoInteractions(aiProvider);
    }

    @Test
    void everyOtherTypeStillTravelsUntouched() {
        when(aiProvider.uploadFile(any())).thenReturn(new ProviderFileReference("file_pdf"));
        when(uploadedFileRepository.save(any(UploadedFile.class))).thenAnswer(inv -> inv.getArgument(0));
        byte[] pdfBytes = { '%', 'P', 'D', 'F', 1, 2, 3 };
        MockMultipartFile file = new MockMultipartFile("file", "rapport.pdf", "application/pdf", pdfBytes);

        uploadService.upload(alice, file);

        ArgumentCaptor<ProviderFileUpload> captor = ArgumentCaptor.forClass(ProviderFileUpload.class);
        verify(aiProvider).uploadFile(captor.capture());
        assertThat(captor.getValue().mediaType()).isEqualTo("application/pdf");
        assertThat(captor.getValue().filename()).isEqualTo("rapport.pdf");
        assertThat(captor.getValue().content()).isEqualTo(pdfBytes);
    }
}
