package fr.claudegateway.teams.meeting;

/** Transition d'état interdite (ex. arrêter une réunion déjà terminée) — 409 (F-128 / SF-128-01). */
public class MeetingStateException extends RuntimeException {
    public MeetingStateException(String message) {
        super(message);
    }
}
