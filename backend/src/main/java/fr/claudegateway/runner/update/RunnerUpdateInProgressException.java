package fr.claudegateway.runner.update;

/** Une mise à jour est déjà en cours sur ce poste (F-111 / SF-111-04) → 409 {@code runner_update_in_progress}. */
public class RunnerUpdateInProgressException extends RuntimeException {

    public RunnerUpdateInProgressException(String message) {
        super(message);
    }
}
