package fr.claudegateway.runner;

/** Erreur d'exécution du runner (I/O, appairage réseau) — distincte de l'erreur d'usage. */
public class RunnerException extends RuntimeException {

    public RunnerException(String message) {
        super(message);
    }

    public RunnerException(String message, Throwable cause) {
        super(message, cause);
    }
}
