package fr.claudegateway.atelier.actions;

/** Une action refusée parce qu'elle ne tient pas ses bornes (F-151 / SF-151-01) — 400. */
public class InvalidTerminalActionException extends RuntimeException {

    public InvalidTerminalActionException(String message) {
        super(message);
    }
}
