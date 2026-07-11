package fr.claudegateway.ai;

import java.util.List;

/**
 * Requête de complétion adressée à un {@link AIProvider}. Contient le modèle cible, l'historique
 * de conversation à envoyer et d'éventuelles pièces jointes rattachées au dernier message
 * utilisateur. Neutre vis-à-vis du fournisseur.
 *
 * @param model       identifiant du modèle (ex. {@code claude-opus-4-8})
 * @param messages    historique ordonné (du plus ancien au plus récent), se terminant par le message utilisateur
 * @param attachments fichiers rattachés au dernier message utilisateur (jamais {@code null} ; vide par défaut)
 * @param apiKey      clé fournisseur à utiliser pour CET appel (mode BYOK, F-03) ; {@code null} => clé
 *                    plateforme (mode Hosted). Provider-neutre : jamais journalisée, jamais persistée.
 * @param system      consigne système optionnelle (top-level {@code system} de l'API) ; {@code null} => aucune.
 */
public record ChatCompletionRequest(String model, List<ChatMessage> messages,
        List<ProviderAttachment> attachments, String apiKey, String system) {

    public ChatCompletionRequest {
        if (attachments == null) {
            attachments = List.of();
        }
    }

    /** Complétion sans consigne système. */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
            List<ProviderAttachment> attachments, String apiKey) {
        this(model, messages, attachments, apiKey, null);
    }

    /** Complétion avec la clé plateforme (mode Hosted). */
    public ChatCompletionRequest(String model, List<ChatMessage> messages, List<ProviderAttachment> attachments) {
        this(model, messages, attachments, null, null);
    }

    /** Complétion sans pièce jointe, clé plateforme. */
    public ChatCompletionRequest(String model, List<ChatMessage> messages) {
        this(model, messages, List.of(), null, null);
    }
}
