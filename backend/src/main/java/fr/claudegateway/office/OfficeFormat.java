package fr.claudegateway.office;

/**
 * Les deux formats Office construits par la gateway (F-129 / SF-129-07).
 *
 * <p><b>Un enum, pas une chaîne libre</b> : le type MIME et l'extension du fichier déposé se
 * déduisent de l'outil appelé, jamais d'un champ rempli par le modèle — un fichier dont le nom sort
 * d'un champ libre finit par mentir sur son contenu.</p>
 */
public enum OfficeFormat {

    /** Le document Word, décrit par des <b>blocs</b>. */
    DOCX("build_document", "docx", "blocks", "document",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),

    /** Le classeur Excel, décrit par des <b>feuilles</b>. */
    XLSX("build_spreadsheet", "xlsx", "sheets", "classeur",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final String tool;
    private final String extension;
    private final String content;
    private final String label;
    private final String contentType;

    OfficeFormat(String tool, String extension, String content, String label, String contentType) {
        this.tool = tool;
        this.extension = extension;
        this.content = content;
        this.label = label;
        this.contentType = contentType;
    }

    /** Le nom de l'outil qui produit ce format. */
    public String tool() {
        return tool;
    }

    /** L'extension du fichier déposé, sans point. */
    public String extension() {
        return extension;
    }

    /** Le champ de la description qui porte le contenu (« blocks », « sheets »). */
    public String contentField() {
        return content;
    }

    /** Le mot employé dans les messages rendus à l'agent. */
    public String label() {
        return label;
    }

    /** Le type MIME du fichier déposé. */
    public String contentType() {
        return contentType;
    }

    /** Le format demandé par ce nom d'outil, ou {@code null} si ce n'en est pas un. */
    public static OfficeFormat ofTool(String tool) {
        for (OfficeFormat format : values()) {
            if (format.tool.equals(tool)) {
                return format;
            }
        }
        return null;
    }
}
