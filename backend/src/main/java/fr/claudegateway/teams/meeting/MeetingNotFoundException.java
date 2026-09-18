package fr.claudegateway.teams.meeting;

/** Réunion inconnue OU d'un autre couple (user_id, host_id) : indiscernables — 404 (F-128 / SF-128-01). */
public class MeetingNotFoundException extends RuntimeException {
    public MeetingNotFoundException(String message) {
        super(message);
    }
}
