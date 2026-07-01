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
 */
public record ChatCompletionRequest(String model, List<ChatMessage> messages, List<ProviderAttachment> attachments) {

    public ChatCompletionRequest {
        if (attachments == null) {
            attachments = List.of();
        }
    }

    /** Complétion sans pièce jointe. */
    public ChatCompletionRequest(String model, List<ChatMessage> messages) {
        this(model, messages, List.of());
    }
}
