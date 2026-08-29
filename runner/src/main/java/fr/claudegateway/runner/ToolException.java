package fr.claudegateway.runner;

/**
 * Échec d'un outil runner (F-38 / SF-38-04), porteur d'un code de la <b>liste close</b> du contrat de
 * messages (§4) : {@code invalid_input}, {@code path_outside_root}, {@code not_found},
 * {@code is_directory}, {@code not_a_file}, {@code too_large}, {@code io_error},
 * {@code unsupported_tool}, {@code timeout}, {@code cancelled}, {@code internal}…
 *
 * <p><b>Règle anti-fuite</b> : le message est en français, court, et ne contient <b>jamais</b> un
 * chemin absolu de la machine — seulement des chemins relatifs à la racine {@code --workspace}.</p>
 */
public final class ToolException extends RuntimeException {

    private final String code;

    public ToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Code d'erreur du contrat (§4). */
    public String code() {
        return code;
    }
}
