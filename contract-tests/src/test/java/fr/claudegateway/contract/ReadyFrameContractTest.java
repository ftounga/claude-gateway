package fr.claudegateway.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.Console;
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
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerOutbound;
import fr.claudegateway.runner.channel.RunnerRegistry;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.host.RunnerShellRecorder;

/**
 * La trame d'ouverture, écrite par le runner, aiguillée par la gateway (F-81 / SF-81-01).
 *
 * <p>C'est la première chose qu'un runner dit en arrivant, et elle porte ce que la gateway ne peut
 * pas deviner : ses <b>capacités</b> — {@code bash} n'y figure pas si la machine l'a refusé — et le
 * <b>genre d'interpréteur</b> élu, dont dépend la syntaxe que la consigne système dictera au modèle.
 * Un nom de champ qui dérive ici ne casse rien de visible : la gateway retombe simplement sur ses
 * valeurs par défaut. Le runner annonce {@code bash}, la gateway ne l'entend pas, et l'outil est
 * refusé sans que personne ne sache pourquoi. C'est la panne d'appairage en plus discret.</p>
 */
class ReadyFrameContractTest {

    private static final UUID HOST = UUID.randomUUID();
    private static final RunnerIdentity IDENTITY =
            new RunnerIdentity(UUID.randomUUID(), UUID.randomUUID(), HOST);

    @Test
    @DisplayName("les capacités annoncées par le runner sont celles que la gateway applique")
    void lesCapacitesAnnonceesSontCellesQueLaGatewayApplique() throws Exception {
        // Un runner lancé avec --no-bash : il n'annonce que les outils fichiers.
        String trameSansBash = readyFrame(List.of("files"));
        // Un runner ordinaire.
        String trameAvecBash = readyFrame(List.of("files", "bash"));

        assertThat(refuseBash(trameSansBash))
                .as("un runner qui n'annonce pas `bash` doit se voir refuser l'outil : c'est le "
                        + "sens même du champ `capabilities`")
                .isTrue();
        assertThat(refuseBash(trameAvecBash))
                .as("SI CE TEST TOMBE, le champ `capabilities` a dérivé : la gateway n'entend plus "
                        + "ce que le runner annonce et refuse `bash` sur toutes les machines")
                .isFalse();
    }

    @Test
    @DisplayName("l'interpréteur élu par le runner est celui que la gateway enregistre")
    void linterpreteurEluEstCeluiQueLaGatewayEnregistre() throws Exception {
        ShellElection elu = ShellElection.elect();
        AtomicReference<String> enregistre = new AtomicReference<>();
        RunnerShellRecorder recorder = (hostId, declared) -> {
            assertThat(hostId)
                    .as("l'identité vient TOUJOURS de la session, jamais d'un champ du message")
                    .isEqualTo(HOST);
            enregistre.set(declared);
        };

        RunnerCallDispatcher gateway = dispatcher(recorder);
        gateway.onFrame(IDENTITY, "ready",
                ContractMappers.gateway().readTree(readyFrame(List.of("files", "bash"))));

        assertThat(enregistre.get())
                .as("SI CE TEST TOMBE, le champ `shell` a dérivé : la consigne système garde son "
                        + "texte POSIX sur un poste Windows, et le modèle dicte des commandes que "
                        + "la machine ne connaît pas (SF-38-27)")
                .isEqualTo(elu.declaredName());
    }

    @Test
    @DisplayName("la version que le runner déclare est celle que la gateway retient")
    void laVersionDeclareeEstCelleQueLaGatewayRetient() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> retenue =
                new java.util.concurrent.atomic.AtomicReference<>();

        RunnerRegistry registry = mock(RunnerRegistry.class);
        when(registry.findLocal(any())).thenReturn(Optional.of(new RunnerConnection(
                HOST, IDENTITY.userId(), IDENTITY.tokenId(), "node-test", OffsetDateTime.now())));
        RunnerCallDispatcher gateway = new RunnerCallDispatcher(registry,
                ContractMappers.gateway(), (hostId, declared) -> {
                }, (hostId, declared) -> retenue.set(declared),
                new ServedRunnerVersion("", ""), 50L);

        gateway.onFrame(IDENTITY, "ready",
                ContractMappers.gateway().readTree(readyFrame(List.of("files"))));

        assertThat(retenue.get())
                .as("SI CE TEST TOMBE, le champ `runnerVersion` a dérivé : l'écran afficherait un "
                        + "vide là où il doit répondre à « son runner est-il à jour ? » (F-81 / "
                        + "SF-81-03)")
                .isEqualTo("9.9.9");
    }

    @Test
    @DisplayName("un type de trame inconnu est ignoré, jamais une erreur")
    void unTypeInconnuEstIgnore() throws Exception {
        RunnerCallDispatcher gateway = dispatcher((hostId, declared) -> {
        });

        // C'est la règle §0 du contrat, et c'est elle qui permet à un runner ancien de cohabiter
        // avec une gateway plus récente. Une trame inconnue ne doit ni lever, ni fermer le canal.
        gateway.onFrame(IDENTITY, "quelque_chose_que_cette_version_ne_connait_pas",
                ContractMappers.gateway().readTree("{\"type\":\"futur\"}"));
    }

    // ------------------------------------------------------------------ montage

    /** La trame d'ouverture réelle, écrite par l'aiguilleur réel du runner. */
    private static String readyFrame(List<String> capacites) {
        try (FrameSender sender = new FrameSender(new Console());
                ToolDispatcher runner = new ToolDispatcher(
                        ToolScopes.fixed((tool, input, context) -> ToolOutcome.ok("")),
                        capacites, ShellElection.elect(), sender, new Console())) {
            return runner.readyFrame("9.9.9");
        }
    }

    /**
     * Vrai si la gateway refuse l'outil {@code bash} après avoir reçu cette trame d'ouverture.
     *
     * <p>Il n'existe pas d'accesseur public aux capacités retenues — et il n'en faut pas : la seule
     * chose qui compte est ce que la gateway <b>fait</b> de ce qu'elle a entendu.</p>
     */
    private static boolean refuseBash(String trameReady) throws Exception {
        RunnerCallDispatcher gateway = dispatcher((hostId, declared) -> {
        });
        gateway.onFrame(IDENTITY, "ready", ContractMappers.gateway().readTree(trameReady));

        RunnerOutbound canalMuet = new RunnerOutbound() {
            @Override
            public void send(String frame) {
                // Une trame émise signifie que la capacité a été acceptée ; l'appel expirera.
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }
        };
        gateway.attachChannel(IDENTITY, canalMuet);

        RunnerCallResult issue = gateway.call(new RunnerTarget(HOST, UUID.randomUUID(), ""),
                "call-" + UUID.randomUUID(), "bash",
                ContractMappers.gateway().createObjectNode().put("command", "echo ok"), 50L);
        return RunnerErrorCodes.UNSUPPORTED_TOOL.equals(issue.errorCode());
    }

    private static RunnerCallDispatcher dispatcher(RunnerShellRecorder recorder) {
        RunnerRegistry registry = mock(RunnerRegistry.class);
        when(registry.findLocal(any())).thenReturn(Optional.of(new RunnerConnection(
                HOST, IDENTITY.userId(), IDENTITY.tokenId(), "node-test", OffsetDateTime.now())));
        when(registry.isConnected(any())).thenReturn(true);
        // `graceMs` au minimum : le seul appel qui va au bout de son délai est celui qu'on veut voir
        // expirer, et l'attente inutile n'apprend rien.
        return new RunnerCallDispatcher(registry, ContractMappers.gateway(), recorder,
                (hostId, declared) -> {
                }, new ServedRunnerVersion("", ""), 50L);
    }
}
