package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>La Vigie fait remonter son état de mise en service</b> (F-122 / SF-122-03), sur le modèle de
 * {@link MomentUploader}.
 *
 * <p>La boucle d'arrière-plan assemble un {@link VigieReadinessReport} ({@link VigieBackground}) et le
 * dépose sur {@code POST /runner/vigie/readiness}. Le jeton du poste voyage dans {@code X-Runner-Token}
 * — <b>jamais en query</b> (D9, il finirait dans les journaux du proxy et de l'ingress) — et c'est lui
 * qui range l'instantané pour le bon poste côté gateway (isolation, SF-122-02).</p>
 *
 * <p>Une remontée qui échoue <b>n'arrête pas</b> la Vigie : la readiness se re-vérifie de toute façon,
 * et la boucle absorbe l'{@link IOException}.</p>
 */
public interface VigieReadinessUploader {

    /**
     * Fait remonter un rapport de readiness.
     *
     * @throws IOException si la remontée n'a pas pu aboutir (elle ne casse pas la boucle)
     */
    void upload(VigieReadinessReport report) throws IOException;

    /** Aucune remontée possible : le volet n'a pas de jeton, ou pas de gateway à joindre. */
    static VigieReadinessUploader unavailable(String why) {
        return report -> {
            throw new IOException(why);
        };
    }

    /** La remontée réelle : un {@code POST} JSON, jeton runner dans l'en-tête dédié. */
    static VigieReadinessUploader over(HttpClient httpClient, String gatewayBaseUrl, String token) {
        ObjectMapper mapper = new ObjectMapper();
        return report -> {
            if (token == null || token.isBlank()) {
                throw new IOException("ce poste n'a pas de jeton runner : rien ne peut remonter");
            }
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(trimTrailingSlash(gatewayBaseUrl) + "/runner/vigie/readiness"))
                    .timeout(Duration.ofSeconds(30))
                    .header("X-Runner-Token", token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(mapper, report)))
                    .build();
            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException(
                            "la gateway a refusé l'instantané (HTTP " + response.statusCode() + ")");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("remontée interrompue", e);
            }
        };
    }

    /** Le corps JSON d'un rapport — les cinq champs, et rien d'autre. */
    static String toJson(ObjectMapper mapper, VigieReadinessReport report) throws IOException {
        try {
            return mapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IOException("rapport de readiness non sérialisable", e);
        }
    }

    private static String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.strip();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
