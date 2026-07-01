package fr.claudegateway.byok;

/**
 * Levée lorsqu'un passage en mode BYOK est demandé alors qu'aucune clé n'est enregistrée.
 * Traduite en {@code 409 byok_mode_conflict}.
 */
public class ByokModeException extends RuntimeException {

    public ByokModeException(String message) {
        super(message);
    }
}
