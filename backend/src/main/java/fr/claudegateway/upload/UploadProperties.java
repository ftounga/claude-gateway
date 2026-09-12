package fr.claudegateway.upload;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Contraintes de validation des téléversements (F-04). Externalisées pour être ajustables sans
 * changement de code (arbitrage réversible : liste blanche MIME + plafond de taille).
 *
 * @param allowedTypes liste blanche des types MIME acceptés (types supportés par Claude)
 * @param maxSize      taille maximale d'un fichier
 */
@ConfigurationProperties(prefix = "app.upload")
public record UploadProperties(List<String> allowedTypes, DataSize maxSize) {

    private static final List<String> DEFAULT_TYPES = List.of(
            "application/pdf",
            "image/png", "image/jpeg", "image/gif", "image/webp",
            "text/plain", "text/markdown", "text/csv");

    public UploadProperties {
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            allowedTypes = DEFAULT_TYPES;
        }
        if (maxSize == null || maxSize.toBytes() <= 0) {
            maxSize = DataSize.ofMegabytes(32);
        }
    }

    /**
     * Liste normalisée (minuscules, sans doublon, ordre de la configuration) des types autorisés.
     *
     * <p>C'est <b>la</b> liste : {@link #allowedTypeSet()} — dont la validation se sert pour refuser
     * — en dérive, et l'endpoint {@code GET /api/file-formats} la publie telle quelle (F-85 /
     * SF-85-01). Une seule liste, deux vues : l'écran ne peut pas proposer ce que le serveur
     * refuse. L'ordre est conservé parce que l'écran s'en sert pour énoncer les formats.
     */
    public List<String> normalizedAllowedTypes() {
        return allowedTypes.stream()
                .map(t -> t.toLowerCase())
                .distinct()
                .collect(Collectors.toList());
    }

    /** Ensemble normalisé (minuscules) des types autorisés, dérivé de {@link #normalizedAllowedTypes()}. */
    public Set<String> allowedTypeSet() {
        return Set.copyOf(normalizedAllowedTypes());
    }

    /** Plafond en octets. */
    public long maxBytes() {
        return maxSize.toBytes();
    }
}
