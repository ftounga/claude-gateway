package fr.claudegateway.ai;

import java.util.List;

/**
 * Catalogue des modèles exposés par la couche fournisseur, indépendant de tout fournisseur concret.
 * Le code métier (chat) dépend de cette abstraction — jamais d'une configuration spécifique à Anthropic —
 * afin de rester compatible avec l'arrivée de futurs fournisseurs/modèles.
 */
public interface ModelCatalog {

    /** Modèle utilisé lorsque la requête n'en précise pas. */
    String defaultModel();

    /** Liste blanche des modèles sélectionnables. */
    List<String> availableModels();

    /** Vrai si le modèle est sélectionnable. */
    default boolean supports(String model) {
        return availableModels().contains(model);
    }
}
