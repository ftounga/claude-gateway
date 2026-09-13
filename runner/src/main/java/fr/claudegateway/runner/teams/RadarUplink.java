package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Ce que la synchro du soir fait remonter</b> (F-100 / SF-100-02) : le battement, la fin et — depuis
 * SF-100-03 — les lots d'échanges, par la route dédiée du canal runner, authentifiée par le jeton du
 * poste.
 *
 * <p>Même doctrine que les captures de F-90 : <b>pas par le résultat d'outil</b>. La collecte dure des
 * minutes, parfois une heure la première fois ; un résultat d'outil n'attend pas si longtemps, et la
 * gateway doit pouvoir dire « en cours » pendant ce temps.</p>
 */
public interface RadarUplink {

    /** Ce que la gateway répond à un battement ou à un lot. */
    enum Answer {
        /** La synchro est toujours en cours : continuer. */
        RUNNING,
        /** La synchro est close (annulée, abandonnée) : s'arrêter sans rien envoyer de plus. */
        STOPPED
    }

    /** Le battement d'une synchro : où elle en est. */
    Answer progress(String syncId, ObjectNode body) throws IOException;

    /** La fin d'une synchro : son issue et sa couverture. */
    Answer finish(String syncId, ObjectNode body) throws IOException;

    /** Un lot d'échanges (SF-100-03) ; rend la réponse de la gateway (reçu, doublon). */
    JsonNode batch(String syncId, ObjectNode body) throws IOException;

    /** Vrai si une remontée est possible (jeton présent). */
    default boolean available() {
        return true;
    }

    /** Aucune remontée possible : le poste n'a pas de jeton. */
    static RadarUplink unavailable(String why) {
        return new RadarUplink() {
            @Override
            public Answer progress(String syncId, ObjectNode body) throws IOException {
                throw new IOException(why);
            }

            @Override
            public Answer finish(String syncId, ObjectNode body) throws IOException {
                throw new IOException(why);
            }

            @Override
            public JsonNode batch(String syncId, ObjectNode body) throws IOException {
                throw new IOException(why);
            }

            @Override
            public boolean available() {
                return false;
            }
        };
    }

    /**
     * La remontée réelle : un {@code POST} JSON par appel, jeton runner dans {@code X-Runner-Token}
     * (<b>jamais</b> en requête : une requête finit dans les journaux du proxy, décision D9).
     */
    static RadarUplink over(HttpClient httpClient, String gatewayBaseUrl, String token) {
        if (token == null || token.isBlank()) {
            return unavailable("ce poste n'a pas de jeton runner : la synchro ne peut rien faire remonter");
        }
        ObjectMapper mapper = new ObjectMapper();
        String base = gatewayBaseUrl == null ? "" : gatewayBaseUrl.strip();
        String root = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/runner/radar/syncs/";
        return new RadarUplink() {
            @Override
            public Answer progress(String syncId, ObjectNode body) throws IOException {
                return answer(post(syncId, "progress", body));
            }

            @Override
            public Answer finish(String syncId, ObjectNode body) throws IOException {
                return answer(post(syncId, "finish", body));
            }

            @Override
            public JsonNode batch(String syncId, ObjectNode body) throws IOException {
                HttpResponse<String> response = post(syncId, "batches", body);
                if (response.statusCode() == 409) {
                    ObjectNode stopped = mapper.createObjectNode();
                    stopped.put("status", "STOPPED");
                    return stopped;
                }
                if (response.statusCode() != 200) {
                    throw new IOException("la gateway a refusé ce lot (HTTP " + response.statusCode() + ")");
                }
                return mapper.readTree(response.body());
            }

            private Answer answer(HttpResponse<String> response) throws IOException {
                if (response.statusCode() == 200) {
                    return Answer.RUNNING;
                }
                if (response.statusCode() == 409) {
                    return Answer.STOPPED;
                }
                throw new IOException("la gateway a répondu HTTP " + response.statusCode());
            }

            private HttpResponse<String> post(String syncId, String what, ObjectNode body) throws IOException {
                HttpRequest request = HttpRequest.newBuilder(URI.create(root + syncId + "/" + what))
                        .timeout(Duration.ofSeconds(60))
                        .header("X-Runner-Token", token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString()))
                        .build();
                try {
                    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("remontée interrompue", e);
                }
            }
        };
    }
}
