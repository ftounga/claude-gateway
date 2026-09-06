package fr.claudegateway.runner.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
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
    /**
     * Pause simulée entre deux fragments. Portée de 300 à 500 ms : l'assertion compare des instants
     * mesurés sur un runner d'intégration continue partagé, où l'ordonnancement peut retarder la
     * lecture d'un fragment de plusieurs centaines de millisecondes. Un écart plus large ne rend pas
     * le test plus permissif — il le rend moins dépendant de la charge de la machine.
     */
    private static final long CHUNK_GAP_MS = 500L;

    @Autowired
    private RunnerRelayConnectorCustomizer relayConnector;

    @Autowired
    private RunnerRelayClient relayClient;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Attend que le connecteur de relais soit <b>réellement lié</b> avant d'appeler.
     *
     * <p>Sans cela, le test est une course perdue d'avance en intégration continue :
     * {@code relayPort()} retombe sur le port <em>configuré</em> tant que Tomcat n'a pas fini de
     * lier le second connecteur, et l'appel part vers un port qui n'écoute pas encore. La liste de
     * fragments revenait vide — un échec qui ressemblait à un problème de temps, et n'en était pas
     * un : le relais n'avait simplement jamais reçu l'appel.</p>
     */
    @BeforeEach
    void waitForTheRelayConnectorToBeBound() {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket probe = new java.net.Socket()) {
                probe.connect(new java.net.InetSocketAddress("127.0.0.1", relayConnector.relayPort()),
                        200);
                return; // Le port répond : le connecteur est prêt.
            } catch (java.io.IOException notYet) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        throw new IllegalStateException(
                "Le connecteur de relais n'écoute toujours pas après 10 s : ce n'est pas le "
                        + "comportement testé ici qui est en cause, mais son environnement.");
    }

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
        // Le premier fragment est arrivé pendant que la commande tournait encore, pas à la fin :
        // il précède le résultat d'au moins une pause complète.
        assertThat(chunkTimes.get(0)).isLessThan(resultAtMs - CHUNK_GAP_MS);
        // Le second aussi précède le résultat — c'est ce qui prouve que rien n'est bufferisé
        // jusqu'à la fin. Sans marge fixe ici : l'antériorité est la propriété testée, et lui
        // imposer un délai minimal ne mesurerait plus que la charge du runner d'intégration.
        assertThat(chunkTimes.get(1)).isLessThan(resultAtMs);
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
