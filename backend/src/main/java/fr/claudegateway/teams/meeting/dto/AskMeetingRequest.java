package fr.claudegateway.teams.meeting.dto;

/**
 * Demande de Q&amp;A sur une réunion (F-128 / SF-128-05).
 *
 * @param question la question libre du consultant (obligatoire, bornée côté service)
 */
public record AskMeetingRequest(String question) {
}
