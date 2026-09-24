package fr.claudegateway.atelier.actions;

/** Une action refusée parce qu'elle ne tient pas ses bornes (F-154 / SF-154-01) — 400. */
public class InvalidTerminalActionException extends RuntimeException {

    public InvalidTerminalActionException(String message) {
        super(message);
    }
}
