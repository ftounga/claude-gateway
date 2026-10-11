package fr.claudegateway.atelier;

/**
 * Correction de passation impossible dans l'état courant du fil (F-117 / SF-117-09) : aucune passation à
 * corriger ({@code no_handoff}) ou un tour en cours ({@code turn_in_progress}). Mappée en {@code 409}
 * avec ce code — un conflit d'état, pas une panne.
 */
public class ThreadHandoffConflictException extends RuntimeException {

    private final String code;

    public ThreadHandoffConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
