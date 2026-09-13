package fr.claudegateway.runner.relay;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PreDestroy;

/**
 * <b>Diffusion</b> des gestes qui doivent atteindre le pod où tourne la boucle — annulation,
 * décision d'autorisation, interruption d'un tour, marque d'interruption de session
 * (F-38 / SF-38-13, contrat du relais §4 à §6).
 *
 * <p>Diffusé et non dirigé : le registre sait où vit la <i>socket du runner</i>, pas où tourne la
 * boucle qui tient le {@code SseEmitter} et la porte de confirmation. Les deux sont indépendants —
 * le navigateur et le runner sont deux clients équilibrés séparément. On parle donc à tous les
 * pairs ; celui qui est concerné agit, les autres répondent « rien à faire ».</p>
 *
 * <p><b>Toujours présent, souvent muet</b> : ce composant existe même quand le relais est éteint
 * (secret vide — dev, tests, production avant configuration). Il ne fait alors rigoureusement rien,
 * et les appelants gardent un seul chemin de code. Une panne de diffusion ne change jamais la
 * réponse rendue à l'utilisateur : elle dégrade vers le comportement d'avant SF-38-13.</p>
 *
 * <p>Journalisation : jamais le secret, jamais un motif d'utilisateur, jamais un identifiant de
 * session complet ailleurs qu'en {@code debug}.</p>
 */
@Component
public class RunnerRelayBroadcaster {

    static final String CONFIRM_PATH = "/api/internal/runner/confirm";
    static final String INTERRUPT_PATH = "/api/internal/atelier/interrupt";
    static final String SESSION_INTERRUPT_PATH = "/api/internal/atelier/session-interrupt";

    private static final Logger log = LoggerFactory.getLogger(RunnerRelayBroadcaster.class);
    /** Exécuteur borné : une diffusion ne doit jamais pouvoir consommer le pool de requêtes. */
    private static final int BROADCAST_THREADS = 4;

    private final RunnerRelayProperties properties;
    private final RelayPeerResolver peerResolver;
    private final RelayPeerClient peerClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(BROADCAST_THREADS,
            runnable -> {
                Thread thread = new Thread(runnable, "relay-broadcast");
                thread.setDaemon(true);
                return thread;
            });

    public RunnerRelayBroadcaster(RunnerRelayProperties properties, RelayPeerResolver peerResolver,
            RelayPeerClient peerClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.peerResolver = peerResolver;
        this.peerClient = peerClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Diffuse une décision d'autorisation aux pairs (contrat §5).
     *
     * @return vrai si un pair détenait la demande et l'a tranchée. Faux ⇒ l'appelant relance son
     *         {@code NoPendingConfirmationException} (409), et la porte qui attendrait ailleurs sans
     *         être atteinte expirera en refus : le silence ne vaut jamais autorisation
     */
    public boolean broadcastConfirm(UUID userId, UUID workspaceId, String callId, boolean allow,
            String reason) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("userId", userId.toString());
        payload.put("workspaceId", workspaceId.toString());
        payload.put("callId", callId);
        payload.put("allow", allow);
        if (reason == null || reason.isBlank()) {
            payload.putNull("reason");
        } else {
            payload.put("reason", reason);
        }
        return broadcast(CONFIRM_PATH, payload.toString()).stream()
                .anyMatch(node -> node.path("resolved").asBoolean(false));
    }

    /** Diffuse l'interruption d'un tour (contrat §6) : best-effort, l'appelant a déjà agi localement. */
    public void broadcastInterrupt(UUID userId, UUID workspaceId, String reason) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("userId", userId.toString());
        payload.put("workspaceId", workspaceId.toString());
        payload.put("reason", reason);
        broadcast(INTERRUPT_PATH, payload.toString());
    }

    /** Diffuse la pose ou le retrait d'une marque d'interruption de session (F-32, contrat §6). */
    public void broadcastSessionInterrupt(String sessionId, boolean mark) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sessionId", sessionId);
        payload.put("mark", mark);
        broadcast(SESSION_INTERRUPT_PATH, payload.toString());
    }

    /**
     * Poste la même enveloppe à tous les pairs, en parallèle, et rend leurs réponses exploitables.
     * Un pair injoignable ou en erreur est simplement absent du résultat : une diffusion partielle
     * n'est pas un échec, c'est le cas nominal d'un cluster qui bouge.
     */
    private List<JsonNode> broadcast(String path, String body) {
        List<String> peers;
        try {
            peers = peerResolver.peerBaseUrls();
        } catch (RuntimeException ex) {
            // Une diffusion ne fait jamais échouer le geste de l'utilisateur : il a déjà été appliqué
            // localement, et le pire cas dégrade vers le comportement d'avant SF-38-13.
            log.warn("Résolution des pairs impossible (chemin={}) : diffusion ignorée", path);
            return List.of();
        }
        if (peers.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<Optional<JsonNode>>> calls = new ArrayList<>(peers.size());
        for (String peer : peers) {
            try {
                calls.add(CompletableFuture.supplyAsync(() -> peerClient.post(peer, path, body),
                        executor));
            } catch (RuntimeException ex) {
                // Exécuteur saturé ou arrêté (arrêt du pod en cours) : ce pair ne sera pas servi.
                log.debug("Diffusion interne non soumise pour un pair (chemin={})", path);
            }
        }
        List<JsonNode> answers = new ArrayList<>(peers.size());
        long deadline = System.currentTimeMillis() + properties.getBroadcastTimeoutMs()
                + properties.getConnectTimeoutMs();
        for (CompletableFuture<Optional<JsonNode>> call : calls) {
            try {
                long remaining = Math.max(1L, deadline - System.currentTimeMillis());
                call.get(remaining, TimeUnit.MILLISECONDS).ifPresent(answers::add);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException | java.util.concurrent.ExecutionException
                    | java.util.concurrent.TimeoutException ex) {
                log.debug("Diffusion interne sans réponse d'un pair (chemin={})", path);
            }
        }
        log.debug("Diffusion interne (chemin={}, pairs={}, réponses={})", path, peers.size(),
                answers.size());
        return answers;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Instance <b>inerte</b>, pour les tests qui construisent un service à la main : aucun pair, aucun
     * appel réseau, exactement le comportement mono-pod.
     */
    public static RunnerRelayBroadcaster disabled() {
        RunnerRelayProperties properties = new RunnerRelayProperties();
        ObjectMapper mapper = new ObjectMapper();
        return new RunnerRelayBroadcaster(properties, new RelayPeerResolver(properties),
                new RelayPeerClient(properties, mapper), mapper);
    }
}
