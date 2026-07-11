package fr.claudegateway.atelier;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de l'Atelier (F-28). Externalisés pour être ajustables sans changement de code.
 *
 * @param storage       fournisseur de stockage : {@code in-memory} (dev/tests, défaut) ou {@code s3} (cluster)
 * @param bucket        bucket S3 (mode {@code s3}) ; fourni par l'environnement
 * @param prefix        préfixe racine des objets ({@code atelier/} par défaut)
 * @param maxTotalBytes taille décompressée totale maximale d'un zip (anti zip-bomb)
 * @param maxEntries    nombre d'entrées maximal d'un zip (anti zip-bomb)
 * @param maxFileBytes  taille maximale d'un fichier décompressé (anti zip-bomb)
 */
@ConfigurationProperties(prefix = "app.atelier")
public record AtelierProperties(
        String storage,
        String bucket,
        String prefix,
        Long maxTotalBytes,
        Integer maxEntries,
        Long maxFileBytes) {

    public AtelierProperties {
        if (storage == null || storage.isBlank()) {
            storage = "in-memory";
        }
        if (prefix == null || prefix.isBlank()) {
            prefix = "atelier/";
        }
        if (maxTotalBytes == null || maxTotalBytes <= 0) {
            maxTotalBytes = 50L * 1024 * 1024; // 50 Mo
        }
        if (maxEntries == null || maxEntries <= 0) {
            maxEntries = 2000;
        }
        if (maxFileBytes == null || maxFileBytes <= 0) {
            maxFileBytes = 2L * 1024 * 1024; // 2 Mo
        }
    }
}
