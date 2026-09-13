package fr.claudegateway.runner.update;

/**
 * Une version refusée avant toute installation (F-111 / SF-111-03). Le {@link #reason() motif} est un
 * code court, remonté tel quel à la gateway ; le message est la phrase dite à l'utilisateur.
 */
public final class UpdateRejectedException extends Exception {

    /** L'empreinte du fichier reçu n'est pas celle annoncée. */
    public static final String SHA256_MISMATCH = "sha256_mismatch";
    /** Signature absente, illisible, ou invalide pour la clé embarquée. */
    public static final String SIGNATURE_INVALID = "signature_invalid";
    /** Le jar signé est celui d'une autre version que celle demandée. */
    public static final String VERSION_MISMATCH = "version_mismatch";
    /** Téléchargement impossible, refusé ou trop gros. */
    public static final String DOWNLOAD_FAILED = "download_failed";
    /** Écriture dans versions/ impossible. */
    public static final String INSTALL_FAILED = "install_failed";

    private final String reason;

    public UpdateRejectedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public UpdateRejectedException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
