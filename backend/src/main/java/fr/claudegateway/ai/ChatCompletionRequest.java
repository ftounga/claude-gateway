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
 * @param maxTokens   plafond de tokens de sortie de CET appel ; {@code null} => le plafond configuré du
 *                    fournisseur s'applique. Provider-neutre : borner sa sortie est une propriété
 *                    générique d'une complétion, pas un détail Anthropic. Introduit par F-54 pour que
 *                    l'aide produit reste courte sans toucher au plafond du chat (F-02).
 */
public record ChatCompletionRequest(String model, List<ChatMessage> messages,
        List<ProviderAttachment> attachments, String apiKey, String system, Integer maxTokens) {

    public ChatCompletionRequest {
        if (attachments == null) {
            attachments = List.of();
        }
        // Une valeur non positive n'est pas une borne : elle est traitée comme « pas de préférence ».
        if (maxTokens != null && maxTokens <= 0) {
            maxTokens = null;
        }
    }

    /** Complétion sans plafond de sortie propre (celui du fournisseur s'applique). */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
            List<ProviderAttachment> attachments, String apiKey, String system) {
        this(model, messages, attachments, apiKey, system, null);
    }

    /** Complétion sans consigne système. */
    public ChatCompletionRequest(String model, List<ChatMessage> messages,
            List<ProviderAttachment> attachments, String apiKey) {
        this(model, messages, attachments, apiKey, null, null);
    }

    /** Complétion avec la clé plateforme (mode Hosted). */
    public ChatCompletionRequest(String model, List<ChatMessage> messages, List<ProviderAttachment> attachments) {
        this(model, messages, attachments, null, null, null);
    }

    /** Complétion sans pièce jointe, clé plateforme. */
    public ChatCompletionRequest(String model, List<ChatMessage> messages) {
        this(model, messages, List.of(), null, null, null);
    }
}
