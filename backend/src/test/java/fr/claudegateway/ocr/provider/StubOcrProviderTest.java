package fr.claudegateway.ocr.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires du fournisseur OCR de secours (dev/tests). Vérifie un comportement déterministe
 * et complet du flux (sync + async) sans dépendance externe.
 */
class StubOcrProviderTest {

    private final StubOcrProvider provider = new StubOcrProvider();

    @Test
    void extractSyncReturnsDeterministicTextAndRaw() {
        OcrDocument document = new OcrDocument("scan.png", "image/png", new byte[] {1, 2, 3});

        OcrExtraction extraction = provider.extractSync(document);

        assertThat(extraction.text()).contains("scan.png").contains("3 octets");
        assertThat(extraction.rawJson()).contains("\"provider\":\"stub\"");
    }

    @Test
    void asyncJobStartsAndCompletesOnFirstPoll() {
        OcrDocument document = new OcrDocument("contrat.pdf", "application/pdf", new byte[] {9});

        String jobId = provider.startAsync(document);
        assertThat(jobId).startsWith("stub-job-");

        OcrJobResult result = provider.pollAsync(jobId);
        assertThat(result.status()).isEqualTo(OcrJobStatus.SUCCEEDED);
        assertThat(result.text()).contains(jobId);
        assertThat(result.rawJson()).contains("stub");
    }
}
