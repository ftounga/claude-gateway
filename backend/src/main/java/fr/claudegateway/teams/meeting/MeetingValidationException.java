package fr.claudegateway.teams.meeting;

/** Requête de réunion invalide (URL, consentement, rétention) — 400 (F-128 / SF-128-01). */
public class MeetingValidationException extends RuntimeException {
    public MeetingValidationException(String message) {
        super(message);
    }
}
