package fr.claudegateway.runner.update;

/** La commande de mise à jour n'a pu être remise à aucun runner (F-111 / SF-111-04) → 409 {@code runner_unavailable}. */
public class RunnerUpdateUndeliveredException extends RuntimeException {

    public RunnerUpdateUndeliveredException(String message) {
        super(message);
    }
}
