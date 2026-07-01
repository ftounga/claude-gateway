package fr.claudegateway.ai;

/**
 * Résultat d'une complétion renvoyée par un {@link AIProvider}.
 *
 * @param content          texte de la réponse assistant
 * @param model            modèle effectivement utilisé (tel que rapporté par le fournisseur)
 * @param inputTokens      tokens consommés en entrée (0 si non rapporté)
 * @param outputTokens     tokens consommés en sortie (0 si non rapporté)
 */
public record ChatCompletionResult(String content, String model, int inputTokens, int outputTokens) {
}
