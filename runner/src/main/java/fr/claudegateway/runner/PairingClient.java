package fr.claudegateway.runner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Client d'appairage (F-38 / SF-38-03) : échange un code d'appairage contre un jeton runner via
 * {@code POST {gateway}/runner/pair} (SF-38-01). Réutilise l'{@link HttpClient} du runner (proxy et
 * truststore d'entreprise déjà configurés).
 */
public final class PairingClient {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public PairingClient(HttpClient httpClient) {
        this.httpClient = httpClient;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Réalise l'appairage. Lève {@link PairingException} en cas de refus (401) ou d'erreur HTTP, sans
     * distinguer code inconnu / expiré / déjà consommé (le serveur renvoie un 401 générique).
     */
    public StoredToken pair(String pairUrl, String code, String label) {
        return pair(pairUrl, code, label, null, false);
    }

    /**
     * Réalise l'appairage en déclarant en plus <b>ce que la gateway ne peut pas savoir</b> : le nom
     * du dossier (F-38 / SF-38-15) et les droits sous lesquels le runner tourne (SF-38-18).
     *
     * <p>Le <b>nom</b> du dossier seulement, jamais le chemin absolu : la gateway n'a aucune raison
     * de connaître l'arborescence de la machine, et le {@code PathGuard} ne lui remonte déjà que des
     * chemins relatifs.</p>
     */
    public StoredToken pair(String pairUrl, String code, String label, String rootName,
            boolean elevated) {
        ObjectNode body = mapper.createObjectNode();
        body.put("code", code);
        if (label != null && !label.isBlank()) {
            body.put("label", label);
        }
        if (rootName != null && !rootName.isBlank()) {
            body.put("rootName", rootName);
        }
        if (elevated) {
            body.put("elevated", true);
        }
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new RunnerException("Sérialisation de la requête d'appairage impossible", e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(pairUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PairingException("Appel d'appairage impossible (" + pairUrl + ") : " + e.getMessage(), -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PairingException("Appairage interrompu", -1);
        }

        int status = response.statusCode();
        if (status == 401) {
            throw new PairingException("Appairage refusé (code invalide, expiré ou déjà utilisé)", 401);
        }
        if (status < 200 || status >= 300) {
            throw new PairingException("Appairage : réponse HTTP " + status, status);
        }
        try {
            StoredToken token = mapper.readValue(response.body(), StoredToken.class);
            if (token.token() == null || token.token().isBlank()) {
                throw new PairingException("Réponse d'appairage sans jeton", status);
            }
            return token;
        } catch (IOException e) {
            throw new PairingException("Réponse d'appairage illisible", status);
        }
    }

    /** Refus ou erreur d'appairage : code de sortie {@code 3}. */
    public static final class PairingException extends RuntimeException {
        private final int httpStatus;

        public PairingException(String message, int httpStatus) {
            super(message);
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }
}
