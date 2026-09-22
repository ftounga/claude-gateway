package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>La remontée du texte d'un enregistrement déposé</b> (F-147 / SF-147-02) : le texte transcrit
 * <b>sur cette machine</b> rejoint la réunion, par le jeton du poste. Symétrique de
 * {@link MeetingAudioUploader} — à ceci près que ce qui monte ici est du <b>texte</b>, et rien d'autre :
 * ni la vidéo, ni l'audio ne quittent le poste, c'est tout l'intérêt du chemin local.
 */
public interface LocalTranscriptUploader {

    /** Une réunion, un texte (ou un échec expliqué) ; rend vrai si la gateway l'a pris. */
    boolean deposit(String meetingId, String text, String failure) throws IOException;

    /** Indisponible (pas de jeton runner) : chaque appel le dit, plutôt que d'échouer sans raison. */
    static LocalTranscriptUploader unavailable(String why) {
        return (meetingId, text, failure) -> {
            throw new IOException(why);
        };
    }

    /**
     * Poste vers {@code {gateway}/runner/teams/meetings/{meetingId}/local-transcript}, en-tête
     * {@code X-Runner-Token}, corps JSON {@code {text, failure}}.
     */
    static LocalTranscriptUploader over(HttpClient httpClient, String gatewayBaseUrl, String token) {
        ObjectMapper mapper = new ObjectMapper();
        return (meetingId, text, failure) -> {
            if (token == null || token.isBlank()) {
                throw new IOException("ce poste n'a pas de jeton runner : le texte ne peut pas remonter");
            }
            String base = gatewayBaseUrl.endsWith("/")
                    ? gatewayBaseUrl.substring(0, gatewayBaseUrl.length() - 1) : gatewayBaseUrl;
            ObjectNode body = mapper.createObjectNode();
            body.put("text", text == null ? "" : text);
            body.put("failure", failure == null ? "" : failure);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(base + "/runner/teams/meetings/" + meetingId + "/local-transcript"))
                    .timeout(Duration.ofMinutes(2))
                    .header("X-Runner-Token", token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("remontée du texte interrompue", e);
            }
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return true;
            }
            throw new IOException("la gateway a refusé le texte de la réunion (HTTP " + response.statusCode() + ")");
        };
    }

    /**
     * Le texte d'un travail terminé, <b>tel qu'on le lit</b> : une réplique par ligne, précédée de son
     * interlocuteur quand il est connu. C'est la forme qu'attend l'exploitation d'une réunion.
     */
    static String textOf(List<TeamsTranscriptCue> cues) {
        if (cues == null || cues.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (TeamsTranscriptCue cue : cues) {
            if (cue == null || !cue.isReadable()) {
                continue;
            }
            String speaker = cue.speakerDisplayName() == null ? "" : cue.speakerDisplayName().strip();
            if (!text.isEmpty()) {
                text.append('\n');
            }
            if (!speaker.isEmpty()) {
                text.append(speaker).append(" : ");
            }
            text.append(cue.text().strip());
        }
        return text.toString();
    }
}
