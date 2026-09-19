package fr.claudegateway.teams.meeting.dto;

/**
 * La réponse de l'agent à une question libre sur une réunion (F-128 / SF-128-05).
 *
 * @param answer la réponse en texte, fondée sur le transcript et/ou les images de la réunion
 */
public record MeetingAnswer(String answer) {
}
