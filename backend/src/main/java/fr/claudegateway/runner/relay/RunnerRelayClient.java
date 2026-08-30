package fr.claudegateway.runner.relay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.channel.RemoteRunnerNode;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;

/**
 * Client du relais interne, côté <b>pod appelant</b> (F-38 / SF-38-12, contrat du relais §3).
 *
 * <p>Il lit la réponse <b>ligne à ligne</b>. C'est le point qui décide de tout : un
 * {@code .body(String.class)} ou n'importe quel {@code retrieve()} bufferiserait le corps entier, et
 * la sortie d'un {@code bash} — jusqu'à 128 Kio agrégés — arriverait d'un bloc à la fin, ce qui
 * annulerait le flux au fil de l'eau que SF-38-07 a livré en local.</p>
 *
 * <p><b>Une seule tentative</b>, jamais de rejeu ni de backoff : rejouer un {@code write_file} serait
 * destructeur. Toute panne dégrade vers l'erreur qui existait déjà — {@code runner_not_on_this_node}
 * quand le pair est injoignable ou répond mal, {@code runner_unavailable} quand le flux s'interrompt
 * avant l'issue (la socket est morte avec le pod).</p>
 *
 * <p>Journalisation : jamais le secret, jamais un {@code chunk}, jamais un contenu de fichier.</p>
 */
@Component
@Conditional(RunnerRelayEnabledCondition.class)
public class RunnerRelayClient {

    private static final Logger log = LoggerFactory.getLogger(RunnerRelayClient.class);
    private static final String CALL_PATH = "/api/internal/runner/call";
    private static final long UNAUTHORIZED_WARN_INTERVAL_MS = 60_000L;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String secret;
    /** Identifiant d'instance de ce pod, porté par {@code X-Relay-Origin} — journal uniquement. */
    private final String originId = UUID.randomUUID().toString();
    private final AtomicLong lastUnauthorizedWarnAt = new AtomicLong(0L);

    public RunnerRelayClient(RunnerRelayProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.secret = properties.getSecret();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        // Délai de lecture entre deux octets, pas délai total : un bash qui parle pendant 2 minutes
        // n'est jamais coupé, un pair muet l'est au bout de readTimeoutMs.
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        // Factory dédiée : ni intercepteur, ni bufferisation héritée du RestClient.Builder partagé.
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Relaie un appel d'outil au pod qui héberge la socket, et rend son issue.
     *
     * @param node      pod distant, adresse issue du registre (jamais dérivée d'un {@code nodeId})
     * @param onChunk   consommateur des fragments de flux, ou {@code null}
     */
    public RunnerCallResult call(RemoteRunnerNode node, UUID workspaceId, String callId, String tool,
            JsonNode input, long timeoutMs, Consumer<String> onChunk) {
        URI uri = URI.create(node.baseUrl() + CALL_PATH);
        try {
            return restClient.post()
                    .uri(uri)
                    .header(RunnerRelayAuthFilter.SECRET_HEADER, secret)
                    .header(RunnerRelayAuthFilter.ORIGIN_HEADER, originId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_NDJSON)
                    .body(payload(workspaceId, callId, tool, input, timeoutMs))
                    .exchange((request, response) -> read(response, onChunk, workspaceId, callId, tool));
        } catch (RuntimeException ex) {
            // Connexion refusée, DNS en échec, timeout de connexion : le pod n'est pas là.
            log.warn("Relais injoignable (node={}, workspace={}, appel={}, outil={}) : {}",
                    node.nodeId(), workspaceId, callId, tool, ex.getClass().getSimpleName());
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE);
        }
    }

    private String payload(UUID workspaceId, String callId, String tool, JsonNode input,
            long timeoutMs) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workspaceId", workspaceId.toString());
        node.put("callId", callId);
        node.put("tool", tool);
        node.set("input", input == null || !input.isObject() ? objectMapper.createObjectNode() : input);
        node.put("timeoutMs", timeoutMs);
        return node.toString();
    }

    private RunnerCallResult read(ClientHttpResponse response, Consumer<String> onChunk,
            UUID workspaceId, String callId, String tool) throws IOException {
        int status = response.getStatusCode().value();
        if (status == 401) {
            warnUnauthorized();
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE);
        }
        if (status != 200) {
            log.warn("Relais refusé par le pair (statut={}, workspace={}, appel={}, outil={})", status,
                    workspaceId, callId, tool);
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(line);
                } catch (IOException ex) {
                    log.warn("Ligne de relais illisible (workspace={}, appel={})", workspaceId, callId);
                    return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE);
                }
                String type = node.path("type").asText("");
                if (RelayNdjson.TYPE_STREAM.equals(type)) {
                    relay(onChunk, node.path("chunk").asText(""));
                } else if (RelayNdjson.TYPE_RESULT.equals(type)) {
                    // Rien n'est attendu après le result : on rend la main sans lire davantage.
                    return RelayNdjson.toResult(node);
                }
                // Tout autre type est ignoré : un pair d'une version plus récente peut en ajouter.
            }
        } catch (SocketTimeoutException ex) {
            log.warn("Relais silencieux au-delà du délai (workspace={}, appel={}, outil={})",
                    workspaceId, callId, tool);
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_TIMEOUT);
        } catch (IOException ex) {
            log.warn("Flux de relais coupé avant l'issue (workspace={}, appel={}, outil={})",
                    workspaceId, callId, tool);
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE);
        }
        // Flux terminé sans ligne `result` : le pod distant est parti avec la socket du runner.
        log.warn("Relais terminé sans issue (workspace={}, appel={}, outil={})", workspaceId, callId,
                tool);
        return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE);
    }

    /** Un fragment relayé ne doit jamais faire échouer l'appel : l'issue prime sur l'affichage. */
    private void relay(Consumer<String> onChunk, String chunk) {
        if (onChunk == null || chunk.isEmpty()) {
            return;
        }
        try {
            onChunk.accept(chunk);
        } catch (RuntimeException ex) {
            log.debug("Consommateur de flux indisponible : fragments suivants ignorés");
        }
    }

    /**
     * Une rotation de secret en cours de rolling update produit des 401 le temps que les deux pods
     * partagent la même valeur : on trace, au plus une fois par minute, et on ne retente jamais sans
     * secret valable.
     */
    private void warnUnauthorized() {
        long now = System.currentTimeMillis();
        long previous = lastUnauthorizedWarnAt.get();
        if (now - previous >= UNAUTHORIZED_WARN_INTERVAL_MS
                && lastUnauthorizedWarnAt.compareAndSet(previous, now)) {
            log.warn("Relais refusé (401) par un pod pair : secret de relais désaccordé "
                    + "(rotation en cours ?)");
        }
    }
}
