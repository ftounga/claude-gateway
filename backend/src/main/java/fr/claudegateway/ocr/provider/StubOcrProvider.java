package fr.claudegateway.ocr.provider;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Implémentation OCR déterministe par défaut (dev, tests, environnements sans AWS). Aucune
 * dépendance externe : elle simule une extraction pour permettre au flux documentaire complet de
 * fonctionner sans crédential AWS. Active tant que {@code app.ocr.provider} n'est pas {@code textract}.
 *
 * <p>Le texte « extrait » est dérivé du contenu de façon stable ; le job asynchrone est
 * immédiatement « terminé » lorsqu'il est interrogé.</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.ocr", name = "provider", havingValue = "stub", matchIfMissing = true)
public class StubOcrProvider implements OcrProvider {

    @Override
    public OcrExtraction extractSync(OcrDocument document) {
        String text = simulate(document);
        return new OcrExtraction(text, rawJson(document, text));
    }

    @Override
    public String startAsync(OcrDocument document) {
        // Jobid opaque déterministe-aléatoire ; encode rien de sensible.
        return "stub-job-" + UUID.randomUUID();
    }

    @Override
    public OcrJobResult pollAsync(String jobId) {
        // En mode stub, un job est considéré terminé dès la première interrogation.
        String text = "OCR-STUB job " + jobId + " terminé.";
        return OcrJobResult.succeeded(text, "{\"provider\":\"stub\",\"jobId\":\"" + jobId + "\"}");
    }

    private static String simulate(OcrDocument document) {
        int bytes = document.content() != null ? document.content().length : 0;
        return "OCR-STUB extraction de '" + document.filename() + "' (" + document.mediaType()
                + ", " + bytes + " octets).";
    }

    private static String rawJson(OcrDocument document, String text) {
        String safeText = text.replace("\"", "\\\"");
        int bytes = document.content() != null ? document.content().length : 0;
        return "{\"provider\":\"stub\",\"bytes\":" + bytes + ",\"text\":\"" + safeText + "\"}";
    }
}
