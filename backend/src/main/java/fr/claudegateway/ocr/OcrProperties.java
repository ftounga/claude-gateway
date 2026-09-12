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
 * @param localTypes   sous-ensemble extrait <b>sur la machine</b>, sans fournisseur (F-86 : Word).
 *                     Testé <b>avant</b> {@code syncTypes} : ces types ne vont chez aucun OCR
 */
@ConfigurationProperties(prefix = "app.ocr")
public record OcrProperties(
        String provider,
        DataSize maxSize,
        List<String> allowedTypes,
        List<String> syncTypes,
        List<String> localTypes) {

    /** Le type MIME d'un document Word {@code .docx} (OOXML WordprocessingML). */
    public static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final List<String> DEFAULT_ALLOWED =
            List.of("application/pdf", "image/png", "image/jpeg", "image/tiff", DOCX_MEDIA_TYPE);
    private static final List<String> DEFAULT_SYNC = List.of("image/png", "image/jpeg");
    private static final List<String> DEFAULT_LOCAL = List.of(DOCX_MEDIA_TYPE);

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
        if (localTypes == null || localTypes.isEmpty()) {
            localTypes = DEFAULT_LOCAL;
        }
    }

    /** Plafond en octets. */
    public long maxBytes() {
        return maxSize.toBytes();
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
                .map(String::toLowerCase)
                .distinct()
                .collect(Collectors.toList());
    }

    /** Ensemble normalisé (minuscules) des types autorisés, dérivé de {@link #normalizedAllowedTypes()}. */
    public Set<String> allowedTypeSet() {
        return Set.copyOf(normalizedAllowedTypes());
    }

    /** Vrai si le type MIME doit être traité en OCR synchrone (image), faux ⇒ asynchrone. */
    public boolean isSyncType(String mediaType) {
        return mediaType != null && syncTypes.stream().anyMatch(t -> t.equalsIgnoreCase(mediaType));
    }

    /**
     * Vrai si le type MIME s'extrait <b>sur la machine</b>, sans fournisseur OCR (F-86 : Word).
     *
     * <p>Interrogé <b>avant</b> {@link #isSyncType(String)} : un type local ne va chez aucun OCR,
     * ni synchrone ni asynchrone. Le routage du pipeline reste ainsi entièrement lisible en
     * configuration, comme {@code sync-types} — le jour où un autre format se lit sur la machine,
     * il s'ajoute sans livraison.
     */
    public boolean isLocalType(String mediaType) {
        return mediaType != null && localTypes.stream().anyMatch(t -> t.equalsIgnoreCase(mediaType));
    }
}
