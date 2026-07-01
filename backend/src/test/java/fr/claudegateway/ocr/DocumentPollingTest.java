package fr.claudegateway.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.ocr.provider.OcrJobResult;
import fr.claudegateway.ocr.provider.OcrProvider;
import fr.claudegateway.ocr.provider.OcrProviderException;

/**
 * Tests unitaires de la complétion des jobs OCR asynchrones (SF-05-02) : transitions de statut
 * selon l'état renvoyé par le fournisseur (mocké), robustesse aux échecs transitoires et aux
 * anomalies (PROCESSING sans job).
 */
@ExtendWith(MockitoExtension.class)
class DocumentPollingTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private OcrProvider ocrProvider;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(documentRepository, ocrProvider, new OcrProperties(null, null, null, null));
    }

    private Document processingPdf(String jobId) {
        return Document.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .filename("contrat.pdf")
                .mediaType("application/pdf")
                .sizeBytes(10)
                .status(DocumentStatus.PROCESSING)
                .ocrMode(OcrMode.ASYNC)
                .providerJobId(jobId)
                .build();
    }

    @Test
    void succeededJobBecomesExtracted() {
        Document doc = processingPdf("job-1");
        when(documentRepository.findByStatus(DocumentStatus.PROCESSING)).thenReturn(List.of(doc));
        when(ocrProvider.pollAsync("job-1"))
                .thenReturn(OcrJobResult.succeeded("Texte du PDF", "{\"provider\":\"stub\"}"));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        int completed = documentService.pollPendingJobs();

        assertThat(completed).isEqualTo(1);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.EXTRACTED);
        assertThat(doc.getExtractedText()).isEqualTo("Texte du PDF");
        assertThat(doc.getTextractRaw()).contains("provider");
    }

    @Test
    void inProgressJobStaysProcessing() {
        Document doc = processingPdf("job-2");
        when(documentRepository.findByStatus(DocumentStatus.PROCESSING)).thenReturn(List.of(doc));
        when(ocrProvider.pollAsync("job-2")).thenReturn(OcrJobResult.inProgress());

        int completed = documentService.pollPendingJobs();

        assertThat(completed).isZero();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void failedJobBecomesFailedWithNeutralMessage() {
        Document doc = processingPdf("job-3");
        when(documentRepository.findByStatus(DocumentStatus.PROCESSING)).thenReturn(List.of(doc));
        when(ocrProvider.pollAsync("job-3")).thenReturn(OcrJobResult.failed());
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        documentService.pollPendingJobs();

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(doc.getErrorMessage()).isEqualTo("Échec de l'extraction OCR.");
    }

    @Test
    void providerExceptionLeavesDocumentProcessingForRetry() {
        Document doc = processingPdf("job-4");
        when(documentRepository.findByStatus(DocumentStatus.PROCESSING)).thenReturn(List.of(doc));
        when(ocrProvider.pollAsync("job-4")).thenThrow(new OcrProviderException("panne transitoire"));

        int completed = documentService.pollPendingJobs();

        assertThat(completed).isZero();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void processingWithoutJobIdIsIgnored() {
        Document doc = processingPdf(null);
        when(documentRepository.findByStatus(DocumentStatus.PROCESSING)).thenReturn(List.of(doc));

        int completed = documentService.pollPendingJobs();

        assertThat(completed).isZero();
        verify(ocrProvider, never()).pollAsync(any());
        verify(documentRepository, never()).save(any());
    }
}
