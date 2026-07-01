package fr.claudegateway.ai;

import java.util.List;

/**
 * Requête de complétion adressée à un {@link AIProvider}. Contient le modèle cible et
 * l'historique de conversation à envoyer. Neutre vis-à-vis du fournisseur.
 *
 * @param model    identifiant du modèle (ex. {@code claude-opus-4-8})
 * @param messages historique ordonné (du plus ancien au plus récent), se terminant par le message utilisateur
 */
public record ChatCompletionRequest(String model, List<ChatMessage> messages) {
}
