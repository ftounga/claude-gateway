package fr.claudegateway.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private UploadService uploadService;

    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        UploadProperties properties = new UploadProperties(null, DataSize.ofMegabytes(32));
        uploadService = new UploadService(aiProvider, uploadedFileRepository, properties);
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
        UploadService service = new UploadService(aiProvider, uploadedFileRepository, tiny);
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
}
