package fr.claudegateway.ai.failover;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AnthropicProperties;
import fr.claudegateway.ai.AnthropicProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ProviderFileReference;
import fr.claudegateway.ai.ProviderFileUpload;
import fr.claudegateway.ai.bedrock.BedrockChannel;
import fr.claudegateway.ai.bedrock.BedrockProperties;

/**
 * Le fournisseur de chat injecté partout (F-277 / SF-277-02) : il choisit le canal de chaque appel selon
 * {@link ProviderFailover}. {@link AnthropicProvider} est marqué {@code @Fallback}.
 *
 * <p>Quand Anthropic répond, c'est le même bean {@link AnthropicProvider}, avec la même requête : rien ne change.
 * L'envoi de fichier ({@link #uploadFile}) reste chez Anthropic, car la Files API n'existe pas sur Bedrock
 * (cadrage D4).</p>
 */
@Component
public class FailoverAIProvider implements AIProvider {

    private final AIProvider anthropic;
    private final AIProvider bedrock;
    private final ProviderFailover failover;

    /** Constructeur d'injection — désigné explicitement, la classe en ayant deux. */
    @Autowired
    public FailoverAIProvider(AnthropicProvider anthropic, AnthropicProperties properties,
            RestClient.Builder restClientBuilder, ObjectMapper objectMapper, BedrockChannel channel,
            ProviderFailover failover) {
        this(anthropic, new AnthropicProvider(channel.anthropicProperties(properties),
                channel.restClientBuilder(restClientBuilder), objectMapper), failover);
    }

    /** Variante de test : les deux canaux fournis. */
    public static FailoverAIProvider of(AIProvider anthropic, AIProvider bedrock, ProviderFailover failover) {
        return new FailoverAIProvider(anthropic, bedrock, failover);
    }

    private FailoverAIProvider(AIProvider anthropic, AIProvider bedrock, ProviderFailover failover) {
        this.anthropic = anthropic;
        this.bedrock = bedrock;
        this.failover = failover;
    }

    @Override
    public ChatCompletionResult complete(ChatCompletionRequest request) {
        return failover.route("chat", request.model(), StringUtils.hasText(request.apiKey()),
                () -> anthropic.complete(request), () -> bedrock.complete(request), () -> true);
    }

    @Override
    public ChatCompletionResult streamComplete(ChatCompletionRequest request, Consumer<String> onDelta) {
        AtomicBoolean shown = new AtomicBoolean();
        Consumer<String> watched = delta -> {
            shown.set(true);
            onDelta.accept(delta);
        };
        return failover.route("chat", request.model(), StringUtils.hasText(request.apiKey()),
                () -> anthropic.streamComplete(request,
                        failover.mode() == BedrockProperties.Mode.OFF ? onDelta : watched),
                () -> bedrock.streamComplete(request, onDelta), () -> !shown.get());
    }

    @Override
    public ProviderFileReference uploadFile(ProviderFileUpload upload) {
        return anthropic.uploadFile(upload);
    }
}
