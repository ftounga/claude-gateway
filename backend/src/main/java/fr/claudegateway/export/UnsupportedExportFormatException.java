package fr.claudegateway.export;

/**
 * Levée lorsqu'un format d'export demandé n'est pas supporté (≠ {@code markdown}/{@code pdf}).
 * Traduite en {@code 400 validation_error} par le {@code GlobalExceptionHandler}. Ne contient jamais
 * de détail sensible.
 */
public class UnsupportedExportFormatException extends RuntimeException {

    public UnsupportedExportFormatException(String requested) {
        super("Format d'export non supporté : '" + requested + "'. Formats acceptés : markdown, pdf.");
    }
}
