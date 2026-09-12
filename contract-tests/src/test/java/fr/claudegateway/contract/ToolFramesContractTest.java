package fr.claudegateway.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.Console;
import fr.claudegateway.runner.FrameRouter;
import fr.claudegateway.runner.FrameSender;
import fr.claudegateway.runner.RunnerIdentity;
import fr.claudegateway.runner.ServedRunnerVersion;
import fr.claudegateway.runner.ShellElection;
import fr.claudegateway.runner.ToolDispatcher;
import fr.claudegateway.runner.ToolOutcome;
import fr.claudegateway.runner.ToolScopes;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerConnection;
import fr.claudegateway.runner.channel.RunnerOutbound;
import fr.claudegateway.runner.channel.RunnerRegistry;
import fr.claudegateway.runner.channel.RunnerTarget;

/**
 * L'appel d'outil complet, de la gateway au runner et retour (F-81 / SF-81-01).
 *
 * <p>C'est le trafic <b>permanent</b> du produit : l'appairage n'a lieu qu'une fois, ces trames-là
 * passent à chaque tour. Une dérive y serait donc plus coûteuse encore — et tout aussi silencieuse,
 * puisque les deux côtés lisent en {@code JsonNode} et qu'un champ absent y vaut une valeur par
 * défaut plutôt qu'une erreur. Renommer {@code project} d'un seul côté ne lèverait rien : tous les
 * tours repartiraient simplement de la racine du poste, sur toutes les machines.</p>
 *
 * <p><b>Le montage.</b> Le canal d'émission de la gateway ({@code RunnerOutbound}) est branché sur
 * l'aiguilleur de trames du runner ({@code FrameRouter}), et le transport du runner
 * ({@code FrameTransport}) est rebranché sur la réception de la gateway. Les deux boucles
 * d'exécution réelles tournent dans la même JVM, reliées par les chaînes qu'elles écrivent
 * elles-mêmes. Aucune trame n'est composée par ce fichier.</p>
 */
class ToolFramesContractTest {

    private static final UUID HOST = UUID.randomUUID();
    private static final UUID PROJET = UUID.randomUUID();
    private static final RunnerIdentity IDENTITY =
            new RunnerIdentity(UUID.randomUUID(), UUID.randomUUID(), HOST);

    private FrameSender sender;
    private ToolDispatcher runner;

    @AfterEach
    void arreterLeRunner() {
        if (runner != null) {
            runner.close();
        }
        if (sender != null) {
            sender.close();
        }
    }

    @Test
    @DisplayName("l'outil, l'entrée, le projet et le délai arrivent au runner tels qu'émis")
    void lappelDOutilTraverse() {
        AtomicReference<String> outilRecu = new AtomicReference<>();
        AtomicReference<JsonNode> entreeRecue = new AtomicReference<>();
        AtomicReference<String> projetRecu = new AtomicReference<>();
        AtomicReference<Long> delaiRecu = new AtomicReference<>();

        RunnerCallDispatcher gateway = brancher(
                projet -> (tool, input, context) -> {
                    projetRecu.set(projet);
                    outilRecu.set(tool);
                    entreeRecue.set(input);
                    delaiRecu.set(context.timeoutMs());
                    return ToolOutcome.ok("contenu du fichier");
                },
                List.of("files", "bash"));

        ObjectNode entree = ContractMappers.gateway().createObjectNode();
        entree.put("path", "src/main/java/Application.java");

        RunnerCallResult issue = gateway.call(
                new RunnerTarget(HOST, PROJET, "clients/acme"), "toolu_01", "read_file", entree,
                4_000L);

        assertThat(issue.ok()).as(issue.errorCode() + " / " + issue.errorMessage()).isTrue();
        assertThat(outilRecu.get())
                .as("le nom d'outil part TEL QUEL vers le modèle et vers le runner : aucun préfixe")
                .isEqualTo("read_file");
        assertThat(entreeRecue.get().path("path").asText())
                .as("l'entrée est copiée verbatim dans la trame")
                .isEqualTo("src/main/java/Application.java");
        assertThat(projetRecu.get())
                .as("SI CE TEST TOMBE, le champ `project` a dérivé : chaque tour repartirait de la "
                        + "RACINE du poste au lieu du dossier du projet, sur toutes les machines, "
                        + "sans qu'aucune erreur ne soit levée (F-48 / SF-48-02)")
                .isEqualTo("clients/acme");
        assertThat(delaiRecu.get())
                .as("SI CE TEST TOMBE, le champ `timeoutMs` a dérivé : le runner armerait son délai "
                        + "par défaut de 30 s là où la gateway en a demandé 4")
                .isEqualTo(4_000L);
    }

    @Test
    @DisplayName("le résultat du runner revient entier : contenu, code de sortie, octets, flux")
    void leResultatTraverse() {
        RunnerCallDispatcher gateway = brancher(
                projet -> (tool, input, context) -> {
                    context.stream("stdout", "première ligne\n");
                    context.stream("stdout", "seconde ligne\n");
                    return new ToolOutcome(true, "sortie complète", true, 4_096L, null, null, 7);
                },
                List.of("files", "bash"));

        List<String> fragments = new CopyOnWriteArrayList<>();
        RunnerCallResult issue = gateway.call(new RunnerTarget(HOST, PROJET, ""), "toolu_02",
                "bash", ContractMappers.gateway().createObjectNode().put("command", "ls"), 4_000L,
                fragments::add);

        assertThat(issue.ok()).isTrue();
        assertThat(issue.content())
                .as("`content` est une chaîne OBLIGATOIRE quand ok=true (contrat §2.4) ; toute "
                        + "autre forme est rendue comme une réponse non conforme")
                .isEqualTo("sortie complète");
        assertThat(issue.truncated()).isTrue();
        assertThat(issue.bytes())
                .as("les octets alimentent l'audit (SF-38-08) : perdus, le journal ment")
                .isEqualTo(4_096L);
        assertThat(issue.exitCode())
                .as("le code de sortie n'existe QUE pour `bash` (contrat §2.4)")
                .isEqualTo(7);
        assertThat(fragments)
                .as("SI CE TEST TOMBE, les trames `tool_stream` ne sont plus relayées : la sortie "
                        + "d'une commande n'apparaîtrait plus qu'À LA FIN, d'un bloc")
                .containsExactly("première ligne\n", "seconde ligne\n");
        assertThat(issue.streamed()).isEqualTo("première ligne\nseconde ligne\n");
    }

    @Test
    @DisplayName("une erreur d'outil revient avec son code de la liste close du contrat")
    void lerreurTraverse() {
        RunnerCallDispatcher gateway = brancher(
                projet -> (tool, input, context) ->
                        ToolOutcome.error("not_found", "Fichier introuvable."),
                List.of("files", "bash"));

        RunnerCallResult issue = gateway.call(new RunnerTarget(HOST, PROJET, ""), "toolu_03",
                "read_file", ContractMappers.gateway().createObjectNode().put("path", "absent"),
                4_000L);

        assertThat(issue.ok()).isFalse();
        assertThat(issue.errorCode())
                .as("SI CE TEST TOMBE, l'objet `error` a dérivé : un échec d'outil deviendrait un "
                        + "`runner_protocol_error` générique et le modèle perdrait la raison réelle")
                .isEqualTo("not_found");
        assertThat(issue.errorMessage()).isEqualTo("Fichier introuvable.");
    }

    // ------------------------------------------------------------------ montage

    /**
     * Branche les deux boucles d'exécution réelles l'une sur l'autre.
     *
     * <p>Sens aller : {@code RunnerOutbound} de la gateway → {@code FrameRouter} du runner.
     * Sens retour : {@code FrameTransport} du runner → {@code onFrame} de la gateway. Les deux
     * chaînes JSON sont celles que le code de production écrit, à l'octet près.</p>
     */
    private RunnerCallDispatcher brancher(ToolScopes scopes, List<String> capacites) {
        RunnerRegistry registry = mock(RunnerRegistry.class);
        when(registry.findLocal(any())).thenReturn(Optional.of(new RunnerConnection(
                HOST, IDENTITY.userId(), IDENTITY.tokenId(), "node-test", OffsetDateTime.now())));
        when(registry.isConnected(any())).thenReturn(true);

        RunnerCallDispatcher gateway = new RunnerCallDispatcher(registry,
                ContractMappers.gateway(), (hostId, declared) -> {
                }, (hostId, declared) -> {
                }, new ServedRunnerVersion("", ""), 2_000L);

        sender = new FrameSender(new Console());
        runner = new ToolDispatcher(scopes, capacites, ShellElection.elect(), sender, new Console());
        // RETOUR : tout ce que le runner émet est aiguillé par la gateway, exactement comme une
        // socket le ferait. Le type est relu dans la trame, jamais supposé.
        sender.attach(frame -> {
            try {
                JsonNode arbre = ContractMappers.runnerFrames().readTree(frame);
                gateway.onFrame(IDENTITY, arbre.path("type").asText(null), arbre);
            } catch (Exception e) {
                throw new IllegalStateException("Trame runner illisible par la gateway : " + frame, e);
            }
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });

        FrameRouter routeur = new FrameRouter(runner, new Console());
        // ALLER : tout ce que la gateway émet est aiguillé par le runner.
        gateway.attachChannel(IDENTITY, new RunnerOutbound() {
            @Override
            public void send(String frame) {
                routeur.route(frame);
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }
        });

        // La trame d'ouverture réelle : c'est elle qui déclare les capacités, et sans elle la
        // gateway retomberait sur ses valeurs par défaut — donc refuserait `bash`.
        gateway.onFrame(IDENTITY, "ready", lire(runner.readyFrame("9.9.9")));
        return gateway;
    }

    private static JsonNode lire(String frame) {
        try {
            return ContractMappers.gateway().readTree(frame);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
