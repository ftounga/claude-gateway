package fr.claudegateway.runner.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.channel.RemoteRunnerNode;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerRegistry;

/**
 * <b>Preuve d'absence de bufferisation</b> du flux relayé (F-38 / SF-38-13).
 *
 * <p>C'est le seul test qui vérifie ce que le multi-pod pouvait casser silencieusement : une chaîne
 * fonctionnellement correcte mais entièrement tamponnée rendrait exactement les mêmes fragments,
 * dans le même ordre — sauf qu'ils arriveraient tous à la fin, et qu'un {@code bash} de deux minutes
 * ne montrerait rien pendant qu'il tourne. On mesure donc le <b>temps</b> : chaque fragment doit être
 * observé par l'appelant pendant que le pod distant travaille encore, franchement avant l'issue.</p>
 *
 * <p>Les deux « pods » sont ici le même contexte, joint par le vrai connecteur interne sur son port
 * dédié : le chemin traversé (contrôleur NDJSON, {@code StreamingResponseBody}, lecture ligne à ligne
 * du client) est celui de la production.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.runner.relay.secret=secret-de-relais-de-test-32-octets!!",
                "app.runner.relay.port=0"
        })
@ActiveProfiles("test")
class RunnerRelayStreamIntegrationTest {

    /** Pause entre deux fragments côté « pod distant » : le temps que l'on cherche à mesurer. */
    private static final long CHUNK_GAP_MS = 300L;

    @Autowired
    private RunnerRelayConnectorCustomizer relayConnector;

    @Autowired
    private RunnerRelayClient relayClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void chunksReachTheCallerWhileTheRemotePodIsStillWorking() {
        RemoteRunnerNode node = new RemoteRunnerNode("pod-voisin",
                "http://127.0.0.1:" + relayConnector.relayPort());
        List<String> chunks = new CopyOnWriteArrayList<>();
        List<Long> chunkTimes = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();

        RunnerCallResult result = relayClient.call(node, UUID.randomUUID(), "toolu_flux", "bash",
                objectMapper.createObjectNode(), 30_000L, chunk -> {
                    chunks.add(chunk);
                    chunkTimes.add((System.nanoTime() - start) / 1_000_000L);
                });
        long resultAtMs = (System.nanoTime() - start) / 1_000_000L;

        // Ordre préservé, et rien n'est perdu.
        assertThat(chunks).containsExactly("ligne 1\n", "ligne 2\n");
        // Le premier fragment est arrivé pendant que la commande tournait encore, pas à la fin.
        assertThat(chunkTimes.get(0)).isLessThan(resultAtMs - CHUNK_GAP_MS);
        assertThat(chunkTimes.get(1)).isLessThan(resultAtMs - CHUNK_GAP_MS / 2);
        // L'agrégat rendu au modèle vient de la ligne `result`, jamais des fragments déjà relayés :
        // les ré-agréger afficherait et compterait la sortie deux fois.
        assertThat(result.ok()).isTrue();
        assertThat(result.streamed()).isEqualTo("ligne 1\nligne 2\n");
        assertThat(result.exitCode()).isZero();
    }

    /**
     * Dispatcher de substitution : il joue le rôle du pod qui tient la socket, en émettant ses
     * fragments espacés dans le temps comme le ferait un vrai {@code tool_stream}.
     */
    @TestConfiguration
    static class SlowDispatcherConfig {

        @Bean
        @Primary
        RunnerCallDispatcher slowDispatcher(RunnerRegistry registry, ObjectMapper objectMapper) {
            return new RunnerCallDispatcher(registry, objectMapper, 5_000L) {
                @Override
                public RunnerCallResult call(UUID workspaceId, String callId, String tool,
                        JsonNode input, long timeoutMs, Consumer<String> onChunk) {
                    emit(onChunk, "ligne 1\n");
                    sleep();
                    emit(onChunk, "ligne 2\n");
                    sleep();
                    return new RunnerCallResult(true, "", false, 0, 700L, null, null, null,
                            "ligne 1\nligne 2\n", false);
                }

                private void emit(Consumer<String> onChunk, String chunk) {
                    if (onChunk != null) {
                        onChunk.accept(chunk);
                    }
                }

                private void sleep() {
                    try {
                        Thread.sleep(CHUNK_GAP_MS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
        }
    }
}
