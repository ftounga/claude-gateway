package fr.claudegateway.docx;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Garde-fous de lecture d'un {@code .docx} (F-86 / SF-86-01). Un {@code .docx} est une
 * <b>archive</b> : sans bornes, une archive de quelques kilooctets peut se décompresser en
 * gigaoctets (zip-bomb). Ces trois bornes reprennent la forme de celles déjà en place sur
 * l'Atelier ({@code app.atelier.max-entries} / {@code max-file-bytes} / {@code max-total-bytes},
 * voir {@code WorkspaceService.extract}) : elles portent sur les octets <b>réellement lus</b>,
 * jamais sur {@code ZipEntry.getSize()}, qui est déclaratif — donc contrôlé par l'attaquant.
 *
 * <p>Les valeurs par défaut sont plus hautes que celles de l'Atelier parce que l'entrée est déjà
 * bornée en amont ({@code app.ocr.max-size} = 20 Mo) et qu'un document bureautique légitime, très
 * compressible, dépasse couramment dix fois sa taille compressée.
 *
 * @param maxEntries    nombre maximal d'entrées traversées avant refus
 * @param maxEntryBytes octets décompressés maximaux pour <b>une</b> entrée
 * @param maxTotalBytes octets décompressés maximaux pour l'archive entière
 */
@ConfigurationProperties(prefix = "app.docx")
public record DocxProperties(Integer maxEntries, Long maxEntryBytes, Long maxTotalBytes) {

    private static final int DEFAULT_MAX_ENTRIES = 2048;
    private static final long DEFAULT_MAX_ENTRY_BYTES = 32L * 1024 * 1024;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 * 1024;

    public DocxProperties {
        if (maxEntries == null || maxEntries <= 0) {
            maxEntries = DEFAULT_MAX_ENTRIES;
        }
        if (maxEntryBytes == null || maxEntryBytes <= 0) {
            maxEntryBytes = DEFAULT_MAX_ENTRY_BYTES;
        }
        if (maxTotalBytes == null || maxTotalBytes <= 0) {
            maxTotalBytes = DEFAULT_MAX_TOTAL_BYTES;
        }
    }
}
