package fr.claudegateway.teams.meeting.dto;

import java.util.List;

/**
 * L'exploitation d'une réunion par l'agent (F-128 / SF-128-05) : la forme lue de la sortie du modèle.
 *
 * @param summary     l'essentiel en quelques phrases
 * @param keyPoints   points clés
 * @param decisions   décisions prises
 * @param actions     actions / engagements
 * @param hasTranscript vrai si NOTRE transcript (SF-128-04) a nourri l'analyse
 * @param hasExternalTranscript vrai si la transcription EXTERNE (client, SF-128-20a) a nourri l'analyse
 * @param imagesUsed  nombre d'images clés (deck) envoyées au modèle en multimodal
 * @param missing     ce qui manque à l'analyse (ex. « transcription absente »), ou {@code null}
 */
public record MeetingInsights(
        String summary,
        List<String> keyPoints,
        List<String> decisions,
        List<String> actions,
        boolean hasTranscript,
        boolean hasExternalTranscript,
        int imagesUsed,
        String missing) {
}
