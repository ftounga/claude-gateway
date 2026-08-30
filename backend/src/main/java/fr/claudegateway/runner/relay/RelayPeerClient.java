package fr.claudegateway.runner.relay;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Un <b>POST court</b> vers un pod pair (F-38 / SF-38-13) : une seule tentative, corps JSON, réponse
 * JSON, et jamais d'exception qui remonte.
 *
 * <p>Rien à voir avec {@link RunnerRelayClient}, qui relaie un appel d'outil et lit un flux NDJSON
 * pendant deux minutes. Ici, les messages sont des gestes brefs — annuler, trancher une
 * autorisation, marquer une interruption : les délais sont serrés, parce qu'un utilisateur attend
 * derrière et qu'un pod muet ne doit pas retarder les autres.</p>
 *
 * <p>Aucun rejeu : rediffuser une décision d'autorisation ou une annulation n'apporterait rien et
 * ferait dépendre le résultat d'un ordre d'arrivée. Un échec est journalisé (jamais le secret,
 * jamais un contenu) et vaut « ce pair n'a rien fait ».</p>
 */
@Component
public class RelayPeerClient {

    private static final Logger log = LoggerFactory.getLogger(RelayPeerClient.class);

    private final ObjectMapper objectMapper;
    private final String secret;
    private final RestClient restClient;

    public RelayPeerClient(RunnerRelayProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.secret = properties.getSecret();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getBroadcastTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Poste {@code body} sur {@code baseUrl + path} et rend le corps de réponse s'il est exploitable.
     *
     * @return le JSON rendu par le pair en 200, ou vide (pair injoignable, refus, corps illisible)
     */
    public Optional<JsonNode> post(String baseUrl, String path, String body) {
        try {
            return restClient.post()
                    .uri(baseUrl + path)
                    .header(RunnerRelayAuthFilter.SECRET_HEADER, secret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200) {
                            log.debug("Pair a refusé le geste interne (chemin={}, statut={})", path,
                                    response.getStatusCode().value());
                            return Optional.<JsonNode>empty();
                        }
                        try {
                            return Optional.ofNullable(objectMapper.readTree(response.getBody()));
                        } catch (IOException ex) {
                            log.debug("Réponse illisible d'un pair (chemin={})", path);
                            return Optional.<JsonNode>empty();
                        }
                    });
        } catch (RuntimeException ex) {
            log.debug("Pair injoignable pour un geste interne (chemin={}) : {}", path,
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
