package fr.claudegateway.export;

/**
 * Format de sortie d'un export (F-14). Le paramètre client {@code format} est traduit en une valeur
 * de cette énumération ; toute valeur inconnue lève {@link UnsupportedExportFormatException} (400).
 */
public enum ExportFormat {

    MARKDOWN("md", "text/markdown;charset=UTF-8"),
    PDF("pdf", "application/pdf");

    private final String extension;
    private final String contentType;

    ExportFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    /** Extension de fichier (sans point) associée au format. */
    public String extension() {
        return extension;
    }

    /** Type MIME à placer dans l'en-tête {@code Content-Type}. */
    public String contentType() {
        return contentType;
    }

    /**
     * Traduit le paramètre client. {@code null}/vide ⇒ {@link #MARKDOWN} (défaut). Insensible à la
     * casse ; accepte {@code md} comme alias de {@code markdown}.
     *
     * @throws UnsupportedExportFormatException si la valeur n'est pas reconnue
     */
    public static ExportFormat fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return MARKDOWN;
        }
        return switch (raw.trim().toLowerCase()) {
            case "markdown", "md" -> MARKDOWN;
            case "pdf" -> PDF;
            default -> throw new UnsupportedExportFormatException(raw);
        };
    }
}
