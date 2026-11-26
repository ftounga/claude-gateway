package fr.claudegateway.ai.bedrock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import fr.claudegateway.ai.AnthropicProperties;

/**
 * Fabrique du canal Bedrock (F-277 / SF-277-01) : ce qu'il faut pour construire une <b>seconde instance</b> des
 * fournisseurs Anthropic qui parle à Bedrock — mêmes réglages (plafonds, délais, relances), client HTTP doté de
 * l'{@link BedrockRequestAdapter}. Ces instances ne sont pas des beans : seul l'aiguillage (SF-277-02) les
 * détient, l'injection par type des fournisseurs existants reste inchangée.
 */
@Component
@EnableConfigurationProperties(BedrockProperties.class)
public class BedrockChannel {

    /**
     * Valeur posée à la place de la clé : les fournisseurs Anthropic refusent d'appeler sans clé, et l'adaptateur
     * retire l'en-tête {@code x-api-key} avant l'envoi. Ce n'est pas un secret.
     */
    static final String IAM_PLACEHOLDER_KEY = "bedrock-iam";

    private final BedrockProperties properties;
    private final BedrockRequestAdapter adapter;

    /** Constructeur d'injection — désigné explicitement, la classe en ayant deux. */
    @Autowired
    public BedrockChannel(BedrockProperties properties) {
        this(properties, BedrockSigner.aws(properties.region()));
    }

    /** Variante de test : signataire fourni. */
    public static BedrockChannel withSigner(BedrockProperties properties, BedrockSigner signer) {
        return new BedrockChannel(properties, signer);
    }

    private BedrockChannel(BedrockProperties properties, BedrockSigner signer) {
        this.properties = properties;
        this.adapter = new BedrockRequestAdapter(properties, signer);
    }

    public BedrockProperties properties() {
        return properties;
    }

    /** Réglages Anthropic du canal : mêmes plafonds et délais, point d'entrée Bedrock, clé factice. */
    public AnthropicProperties anthropicProperties(AnthropicProperties source) {
        return new AnthropicProperties(IAM_PLACEHOLDER_KEY, properties.baseUrl(), source.version(),
                source.defaultModel(), source.fastModel(), source.models(), source.maxTokens(),
                source.agentMaxTokens(), source.timeout(), source.agentTimeout(), source.agentMaxAttempts());
    }

    /** Un client HTTP doté de l'adaptateur, cloné : le constructeur partagé n'est jamais modifié. */
    public RestClient.Builder restClientBuilder(RestClient.Builder source) {
        return source.clone().requestInterceptor(adapter);
    }
}
