package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * <b>La remontée de l'audio d'une réunion</b> (F-128 / SF-128-02) — l'audio capturé dans l'onglet part
 * du runner vers la gateway, jamais par la page. Symétrique de {@link MomentUploader} : les octets
 * voyagent hors du modèle, le jeton runner reste dans le runner (jamais exposé à la page).
 */
public interface MeetingAudioUploader {

    /** Téléverse l'audio d'une réunion ; rend la taille acceptée par la gateway. */
    long upload(String workspaceId, String meetingId, byte[] audio) throws IOException;

    /** Indisponible (pas de jeton runner) : chaque appel le dit, plutôt que d'échouer sans raison. */
    static MeetingAudioUploader unavailable(String why) {
        return (workspaceId, meetingId, audio) -> {
            throw new IOException(why);
        };
    }

    /**
     * Un uploader qui poste vers {@code {gateway}/runner/teams/meetings/{meetingId}/audio?workspaceId=…},
     * en-tête {@code X-Runner-Token}, corps = octets {@code audio/webm}.
     */
    static MeetingAudioUploader over(HttpClient httpClient, String gatewayBaseUrl, String token) {
        return (workspaceId, meetingId, audio) -> {
            if (token == null || token.isBlank()) {
                throw new IOException("ce poste n'a pas de jeton runner : l'audio ne peut pas remonter");
            }
            String base = gatewayBaseUrl.endsWith("/")
                    ? gatewayBaseUrl.substring(0, gatewayBaseUrl.length() - 1) : gatewayBaseUrl;
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(base + "/runner/teams/meetings/" + meetingId + "/audio"
                                    + "?workspaceId=" + workspaceId))
                    .timeout(Duration.ofMinutes(10))
                    .header("X-Runner-Token", token)
                    .header("Content-Type", MeetingTabCapture.MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(audio))
                    .build();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("remontée de l'audio interrompue", e);
            }
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return audio.length;
            }
            throw new IOException("la gateway a refusé l'audio de la réunion (HTTP "
                    + response.statusCode() + ")");
        };
    }
}
