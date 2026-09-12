package fr.claudegateway.fileformats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import fr.claudegateway.ocr.OcrProperties;
import fr.claudegateway.upload.UploadProperties;

/**
 * Une seule liste, deux vues (F-85 / SF-85-01). {@code allowedTypeSet()} — la vue dont la
 * validation se sert pour refuser — doit être <b>dérivée</b> de {@code normalizedAllowedTypes()} —
 * la vue que l'endpoint publie à l'écran. Si ces deux vues cessaient d'avoir le même contenu,
 * l'écran proposerait ce que le serveur refuse : c'est le défaut que F-85 corrige.
 */
class AllowedTypesDerivationTest {

    @Test
    void uploadNormalizesLowercaseAndDeduplicatesWhileKeepingConfigurationOrder() {
        UploadProperties properties = new UploadProperties(
                List.of("application/PDF", "image/png", "APPLICATION/pdf", "text/csv"),
                DataSize.ofMegabytes(32));

        assertThat(properties.normalizedAllowedTypes())
                .containsExactly("application/pdf", "image/png", "text/csv");
        assertThat(properties.allowedTypeSet())
                .containsExactlyInAnyOrderElementsOf(properties.normalizedAllowedTypes());
    }

    @Test
    void ocrNormalizesLowercaseAndDeduplicatesWhileKeepingConfigurationOrder() {
        OcrProperties properties = new OcrProperties(
                "stub",
                DataSize.ofMegabytes(20),
                List.of("application/PDF", "image/TIFF", "application/pdf"),
                List.of("image/png"));

        assertThat(properties.normalizedAllowedTypes())
                .containsExactly("application/pdf", "image/tiff");
        assertThat(properties.allowedTypeSet())
                .containsExactlyInAnyOrderElementsOf(properties.normalizedAllowedTypes());
    }

    @Test
    void emptyConfigurationFallsBackToDefaultsOnBothViews() {
        UploadProperties upload = new UploadProperties(List.of(), null);
        OcrProperties ocr = new OcrProperties(null, null, List.of(), List.of());

        assertThat(upload.normalizedAllowedTypes()).contains("application/pdf");
        assertThat(upload.allowedTypeSet())
                .containsExactlyInAnyOrderElementsOf(upload.normalizedAllowedTypes());
        assertThat(ocr.normalizedAllowedTypes())
                .containsExactly("application/pdf", "image/png", "image/jpeg", "image/tiff");
        assertThat(ocr.allowedTypeSet())
                .containsExactlyInAnyOrderElementsOf(ocr.normalizedAllowedTypes());
    }
}
