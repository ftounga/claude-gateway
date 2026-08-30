package fr.claudegateway.runner.relay;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.runner.channel.RemoteRunnerNode;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerRegistry;

/**
 * Décide <b>où</b> exécuter un appel d'outil (F-38 / SF-38-12) : ici, chez le pod voisin, ou nulle
 * part.
 *
 * <p>C'est le seul endroit du code où cette décision est prise. {@code RunnerToolGateway} passe par
 * lui ; le contrôleur du relais, lui, appelle {@link RunnerCallDispatcher} <b>directement</b>. Un
 * second saut est donc inexprimable : un appel relayé ne peut pas être relayé une nouvelle fois, et
 * cette garantie tient à la structure du code, pas à un compteur de sauts qu'il faudrait penser à
 * décrémenter.</p>
 *
 * <p>Trois cas, dans cet ordre :</p>
 * <ol>
 *   <li>socket <b>locale</b> → appel local, chemin strictement inchangé ;</li>
 *   <li>adresse <b>distante</b> connue, différente de la nôtre, relais actif → un saut HTTP, une
 *       seule tentative ;</li>
 *   <li>sinon → l'erreur qui existait déjà : {@code runner_not_on_this_node} si un runner est présent
 *       ailleurs, {@code runner_unavailable} sinon.</li>
 * </ol>
 */
@Component
public class RunnerCallRouter {

    private static final Logger log = LoggerFactory.getLogger(RunnerCallRouter.class);

    private final RunnerRegistry registry;
    private final RunnerCallDispatcher dispatcher;
    private final RunnerRelayProperties properties;
    private final ObjectProvider<RunnerRelayClient> relayClient;

    public RunnerCallRouter(RunnerRegistry registry, RunnerCallDispatcher dispatcher,
            RunnerRelayProperties properties, ObjectProvider<RunnerRelayClient> relayClient) {
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.relayClient = relayClient;
    }

    /** Appel sans relais de flux. */
    public RunnerCallResult call(UUID workspaceId, String callId, String tool, JsonNode input,
            long timeoutMs) {
        return call(workspaceId, callId, tool, input, timeoutMs, null);
    }

    /** Appel avec relais de flux : {@code onChunk} reçoit les fragments, local ou distant. */
    public RunnerCallResult call(UUID workspaceId, String callId, String tool, JsonNode input,
            long timeoutMs, Consumer<String> onChunk) {

        if (registry.findLocal(workspaceId).isPresent()) {
            return dispatcher.call(workspaceId, callId, tool, input, timeoutMs, onChunk);
        }
        RemoteRunnerNode remote = relayTarget(workspaceId);
        if (remote != null) {
            log.debug("Appel relayé vers un pod pair (node={}, workspace={}, outil={})",
                    remote.nodeId(), workspaceId, tool);
            return relayClient.getObject()
                    .call(remote, workspaceId, callId, tool, input, timeoutMs, onChunk);
        }
        return RunnerCallResult.backendError(registry.isConnected(workspaceId)
                ? RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE
                : RunnerErrorCodes.RUNNER_UNAVAILABLE);
    }

    /**
     * Pod pair vers lequel relayer, ou {@code null} s'il n'y en a pas d'exploitable : relais éteint,
     * adresse inconnue (présence non convergée), ou adresse égale à la nôtre — auquel cas relayer
     * reviendrait à s'appeler soi-même pour se voir répondre la même chose.
     */
    private RemoteRunnerNode relayTarget(UUID workspaceId) {
        if (!properties.isEnabled() || relayClient.getIfAvailable() == null) {
            return null;
        }
        Optional<RemoteRunnerNode> remote = registry.findRemote(workspaceId);
        if (remote.isEmpty() || remote.get().baseUrl().isBlank()) {
            return null;
        }
        return remote.get().baseUrl().equals(properties.selfBaseUrl()) ? null : remote.get();
    }
}
