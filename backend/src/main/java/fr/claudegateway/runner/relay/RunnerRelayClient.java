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
import fr.claudegateway.runner.channel.RunnerTarget;

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
    private static final String CANCEL_PATH = "/api/internal/runner/cancel";
    private static final long UNAUTHORIZED_WARN_INTERVAL_MS = 60_000L;

    private final RestClient restClient;
    private final RelayPeerClient peerClient;
    private final ObjectMapper objectMapper;
    private final String secret;
    /** Identifiant d'instance de ce pod, porté par {@code X-Relay-Origin} — journal uniquement. */
    private final String originId = UUID.randomUUID().toString();
    private final AtomicLong lastUnauthorizedWarnAt = new AtomicLong(0L);

    public RunnerRelayClient(RunnerRelayProperties properties, RelayPeerClient peerClient,
            ObjectMapper objectMapper) {
        this.peerClient = peerClient;
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
    public RunnerCallResult call(RemoteRunnerNode node, RunnerTarget target, String callId,
            String tool, JsonNode input, long timeoutMs, Consumer<String> onChunk) {
        URI uri = URI.create(node.baseUrl() + CALL_PATH);
        UUID hostId = target.hostId();
        try {
            RunnerCallResult result = restClient.post()
                    .uri(uri)
                    .header(RunnerRelayAuthFilter.SECRET_HEADER, secret)
                    .header(RunnerRelayAuthFilter.ORIGIN_HEADER, originId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_NDJSON)
                    .body(payload(target, callId, tool, input, timeoutMs))
                    .exchange((request, response) -> read(response, onChunk, hostId, callId, tool));
            if (RunnerErrorCodes.RUNNER_TIMEOUT.equals(result.errorCode())) {
                // Le pair est resté muet au-delà du délai de lecture : il tient peut-être encore une
                // commande sur la machine de l'utilisateur. On lui demande de l'arrêter, best-effort
                // et sans jamais retenter (contrat du relais §7).
                cancelQuietly(node, target.workspaceId());
            }
            return result;
        } catch (RuntimeException ex) {
            // Connexion refusée, DNS en échec, timeout de connexion : le pod n'est pas là.
            log.warn("Relais injoignable (node={}, poste={}, appel={}, outil={}) : {}",
                    node.nodeId(), hostId, callId, tool, ex.getClass().getSimpleName());
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE,
                    "Le pair est injoignable (" + ex.getClass().getSimpleName() + ").");
        }
    }

    /**
     * Annulation <b>dirigée</b> vers le pod dont on n'a pas reçu l'issue à temps (contrat §7). Seul
     * cas où cette route n'est pas diffusée : ici, on sait exactement à qui parler. Un échec est
     * ignoré — c'est déjà un chemin de rattrapage.
     */
    private void cancelQuietly(RemoteRunnerNode node, UUID workspaceId) {
        ObjectNode payload = objectMapper.createObjectNode();
        // L'annulation vise le PROJET : plusieurs projets d'un même poste peuvent tourner en
        // parallèle, et l'appel abandonné n'est celui que de l'un d'eux (F-48 / SF-48-01).
        payload.put("workspaceId", workspaceId.toString());
        payload.put("reason", "timeout");
        peerClient.post(node.baseUrl(), CANCEL_PATH, payload.toString());
    }

    private String payload(RunnerTarget target, String callId, String tool, JsonNode input,
            long timeoutMs) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("hostId", target.hostId().toString());
        node.put("workspaceId", target.workspaceId().toString());
        node.put("project", target.safeProjectPath());
        node.put("callId", callId);
        node.put("tool", tool);
        node.set("input", input == null || !input.isObject() ? objectMapper.createObjectNode() : input);
        node.put("timeoutMs", timeoutMs);
        return node.toString();
    }

    private RunnerCallResult read(ClientHttpResponse response, Consumer<String> onChunk,
            UUID hostId, String callId, String tool) throws IOException {
        int status = response.getStatusCode().value();
        if (status == 401) {
            warnUnauthorized();
            // Le statut voyage dans le message : un pair qui REFUSE et un pair INJOIGNABLE rendent
            // le même code, et sans cette précision les deux sont indiscernables dans un rapport
            // d'incident comme dans un échec de test.
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE,
                    "Le pair a refusé le relais (401 : secret de relais rejeté).");
        }
        if (status != 200) {
            log.warn("Relais refusé par le pair (statut={}, poste={}, appel={}, outil={})", status,
                    hostId, callId, tool);
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE,
                    "Le pair a refusé le relais (statut HTTP " + status
                            + (status == 404 ? " : requête reçue hors du port de relais)." : ")."));
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
                    log.warn("Ligne de relais illisible (poste={}, appel={})", hostId, callId);
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
            log.warn("Relais silencieux au-delà du délai (poste={}, appel={}, outil={})",
                    hostId, callId, tool);
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_TIMEOUT);
        } catch (IOException ex) {
            log.warn("Flux de relais coupé avant l'issue (poste={}, appel={}, outil={})",
                    hostId, callId, tool);
            return RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE);
        }
        // Flux terminé sans ligne `result` : le pod distant est parti avec la socket du runner.
        log.warn("Relais terminé sans issue (poste={}, appel={}, outil={})", hostId, callId,
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
