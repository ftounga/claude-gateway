package fr.claudegateway.teams.meeting.dto;

/**
 * Requête de <b>collage</b> d'une transcription externe sur une réunion (F-128 / SF-128-20a).
 *
 * @param text   le texte de la transcription du client, collé tel quel (obligatoire, non vide)
 * @param source libellé de la source (ex. « Transcription Teams (client) »), optionnel — un défaut est
 *               posé côté service si absent
 */
public record SetExternalTranscriptRequest(String text, String source) {
}
