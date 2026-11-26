package fr.claudegateway.agent;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import fr.claudegateway.ai.AnthropicProperties;
import fr.claudegateway.ai.bedrock.BedrockChannel;
import fr.claudegateway.ai.bedrock.BedrockProperties;
import fr.claudegateway.ai.failover.ProviderFailover;

/**
 * Le fournisseur d'agent injecté partout (F-277 / SF-277-02) : il choisit le canal (Anthropic ou Bedrock) de
 * chaque tour, selon {@link ProviderFailover}. {@link AnthropicAgentProvider} est marqué {@code @Fallback} : il
 * n'est injecté par type que là où on le demande nommément (ici).
 *
 * <p>Quand Anthropic répond, rien ne change : c'est <b>le même</b> bean {@link AnthropicAgentProvider}, appelé
 * avec la <b>même</b> requête et le même écouteur (corps, en-têtes, relances, flux et mesure identiques).</p>
 */
@Component
public class FailoverAgentProvider implements AiAgentProvider {

    private final AiAgentProvider anthropic;
    private final AiAgentProvider bedrock;
    private final ProviderFailover failover;

    /** Constructeur d'injection — désigné explicitement, la classe en ayant deux. */
    @Autowired
    public FailoverAgentProvider(AnthropicAgentProvider anthropic, AnthropicProperties properties,
            AgentApiFeaturesProperties features, RestClient.Builder restClientBuilder, BedrockChannel channel,
            ProviderFailover failover) {
        this(anthropic, new AnthropicAgentProvider(channel.anthropicProperties(properties), features,
                channel.restClientBuilder(restClientBuilder)), failover);
    }

    /** Variante de test : les deux canaux fournis. */
    static FailoverAgentProvider of(AiAgentProvider anthropic, AiAgentProvider bedrock, ProviderFailover failover) {
        return new FailoverAgentProvider(anthropic, bedrock, failover);
    }

    private FailoverAgentProvider(AiAgentProvider anthropic, AiAgentProvider bedrock, ProviderFailover failover) {
        this.anthropic = anthropic;
        this.bedrock = bedrock;
        this.failover = failover;
    }

    @Override
    public Set<String> supportedMediaTypes() {
        return anthropic.supportedMediaTypes();
    }

    @Override
    public AgentTurn nextTurn(AgentTurnRequest request) {
        return failover.route("agent", request.model(), StringUtils.hasText(request.apiKey()),
                () -> anthropic.nextTurn(request), () -> bedrock.nextTurn(request), () -> true);
    }

    @Override
    public AgentTurn nextTurn(AgentTurnRequest request, AgentTextListener textListener) {
        AgentTextListener sink = textListener == null ? AgentTextListener.NONE : textListener;
        AtomicBoolean shown = new AtomicBoolean();
        // Cadrage D8 : la bascule n'a lieu que si rien n'est encore parti à l'écran.
        AgentTextListener watched = delta -> {
            shown.set(true);
            sink.onTextDelta(delta);
        };
        return failover.route("agent", request.model(), StringUtils.hasText(request.apiKey()),
                () -> anthropic.nextTurn(request, failover.mode() == BedrockProperties.Mode.OFF
                        ? textListener : watched),
                () -> bedrock.nextTurn(request, textListener), () -> !shown.get());
    }
}
