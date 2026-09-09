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

    /**
     * Modèle <b>rapide et économique</b> du catalogue, pour les appels courts et utilitaires de la
     * plateforme (aide produit, F-54). Le domaine exprime ainsi un <i>besoin</i> — une réponse
     * courte, tout de suite, à faible coût — sans nommer aucun fournisseur ni aucun modèle.
     *
     * <p>Repli par défaut : le modèle par défaut du catalogue. Une implémentation qui n'expose pas
     * de modèle rapide reste ainsi utilisable, au prix d'un appel plus coûteux.</p>
     */
    default String fastModel() {
        return defaultModel();
    }

    /** Liste blanche des modèles sélectionnables. */
    List<String> availableModels();

    /** Vrai si le modèle est sélectionnable. */
    default boolean supports(String model) {
        return availableModels().contains(model);
    }
}
