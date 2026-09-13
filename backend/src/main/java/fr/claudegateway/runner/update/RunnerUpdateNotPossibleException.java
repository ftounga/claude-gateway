package fr.claudegateway.runner.update;

/** Rien à mettre à jour d'un clic sur ce poste (F-111 / SF-111-04) → 409 {@code runner_update_not_possible}. */
public class RunnerUpdateNotPossibleException extends RuntimeException {

    private final String reason;

    public RunnerUpdateNotPossibleException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** {@code up_to_date}, {@code no_launcher}, {@code java}, {@code not_signed}, {@code contract}, {@code unknown}. */
    public String reason() {
        return reason;
    }
}
