package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * <b>La remontée des images clés d'une réunion</b> (F-128 / SF-128-03) — une image par appel, comme les
 * moments (F-90). Le jeton runner reste dans le runner, jamais exposé à la page.
 */
public interface MeetingImageUploader {

    /** Téléverse une image clé ; rend le nombre d'images alors rattachées à la réunion. */
    int upload(String workspaceId, String meetingId, byte[] image) throws IOException;

    static MeetingImageUploader unavailable(String why) {
        return (workspaceId, meetingId, image) -> {
            throw new IOException(why);
        };
    }

    static MeetingImageUploader over(HttpClient httpClient, String gatewayBaseUrl, String token) {
        return (workspaceId, meetingId, image) -> {
            if (token == null || token.isBlank()) {
                throw new IOException("ce poste n'a pas de jeton runner : les images ne peuvent pas remonter");
            }
            String base = gatewayBaseUrl.endsWith("/")
                    ? gatewayBaseUrl.substring(0, gatewayBaseUrl.length() - 1) : gatewayBaseUrl;
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(base + "/runner/teams/meetings/" + meetingId + "/images"
                                    + "?workspaceId=" + workspaceId))
                    .timeout(Duration.ofMinutes(2))
                    .header("X-Runner-Token", token)
                    .header("Content-Type", "image/jpeg")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(image))
                    .build();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("remontée d'image interrompue", e);
            }
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return 1;
            }
            throw new IOException("la gateway a refusé l'image de réunion (HTTP " + response.statusCode() + ")");
        };
    }
}
