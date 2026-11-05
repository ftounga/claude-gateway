package fr.claudegateway.runner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * <b>Le relevé de départ</b> (F-247 / SF-247-09) : à l'arrêt propre, le runner prévient la gateway
 * ({@code POST /runner/departure}) et lui laisse au plus {@link RunnerShutdown#DEPARTURE} pour relever l'état du poste
 * par la liaison encore ouverte. Ne lève jamais ; une gateway ancienne (404) ou muette n'allonge pas l'arrêt.
 */
public final class RunnerDeparture {

    private RunnerDeparture() {
    }

    /** La demande, prête à être lancée par le crochet d'arrêt. */
    public static Runnable request(HttpClient client, String url, String token, Duration timeout) {
        return () -> {
            if (client == null || url == null || token == null || token.isBlank()) {
                return;
            }
            try {
                client.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                        .header("Accept", "application/json").header(RunnerHeaders.TOKEN, token)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            } catch (IOException | RuntimeException e) {
                // départ sans relevé : Jarvis dira l'âge du dernier
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
