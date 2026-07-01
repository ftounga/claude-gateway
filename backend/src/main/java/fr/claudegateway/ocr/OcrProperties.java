package fr.claudegateway.ocr;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Contraintes du pipeline OCR (F-05). Externalisées pour être ajustables sans changement de code
 * (arbitrages réversibles : liste blanche MIME, plafond de taille, routage sync/async, fournisseur).
 *
 * @param provider     fournisseur OCR actif ({@code stub} par défaut, {@code textract} en cluster)
 * @param maxSize      taille maximale d'un document soumis
 * @param allowedTypes liste blanche des types MIME acceptés
 * @param syncTypes    sous-ensemble traité en OCR synchrone (images) ; le reste part en asynchrone
 */
@ConfigurationProperties(prefix = "app.ocr")
public record OcrProperties(
        String provider,
        DataSize maxSize,
        List<String> allowedTypes,
        List<String> syncTypes) {

    private static final List<String> DEFAULT_ALLOWED =
            List.of("application/pdf", "image/png", "image/jpeg", "image/tiff");
    private static final List<String> DEFAULT_SYNC = List.of("image/png", "image/jpeg");

    public OcrProperties {
        if (provider == null || provider.isBlank()) {
            provider = "stub";
        }
        if (maxSize == null || maxSize.toBytes() <= 0) {
            maxSize = DataSize.ofMegabytes(20);
        }
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            allowedTypes = DEFAULT_ALLOWED;
        }
        if (syncTypes == null || syncTypes.isEmpty()) {
            syncTypes = DEFAULT_SYNC;
        }
    }

    /** Plafond en octets. */
    public long maxBytes() {
        return maxSize.toBytes();
    }

    /** Ensemble normalisé (minuscules) des types autorisés. */
    public Set<String> allowedTypeSet() {
        return allowedTypes.stream().map(String::toLowerCase).collect(Collectors.toSet());
    }

    /** Vrai si le type MIME doit être traité en OCR synchrone (image), faux ⇒ asynchrone. */
    public boolean isSyncType(String mediaType) {
        return mediaType != null && syncTypes.stream().anyMatch(t -> t.equalsIgnoreCase(mediaType));
    }
}
