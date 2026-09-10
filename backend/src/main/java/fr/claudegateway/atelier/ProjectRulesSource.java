package fr.claudegateway.atelier;

import java.util.UUID;

/**
 * D'où la boucle tire les <b>règles de gouvernance</b> d'un projet (F-51 / SF-51-04).
 *
 * <p><b>Pourquoi une interface plutôt qu'une dépendance directe.</b> C'est le geste que F-50 a déjà
 * fait avec {@code AtelierCheckpointRunner} : {@link AtelierChatService} n'a pas à connaître le
 * catalogue de gouvernance pour composer sa consigne système, et les tests de la boucle continuent
 * de se monter sans le module. {@link #NONE} rend le comportement d'avant F-51, à l'octet près.</p>
 *
 * <p>Le texte rendu est déjà <b>composé et borné</b> par son fournisseur : la boucle l'insère, elle
 * ne le met pas en forme. Elle n'a pas non plus à savoir qu'un paquet existe.</p>
 */
@FunctionalInterface
public interface ProjectRulesSource {

    /** Aucune règle, jamais : la consigne système est celle d'avant F-51. */
    ProjectRulesSource NONE = (userId, workspaceId) -> null;

    /**
     * Les règles à ajouter à la consigne système de ce projet.
     *
     * @param userId      propriétaire du tour (isolation : les règles d'un autre compte ne doivent
     *                    jamais remonter)
     * @param workspaceId projet du tour
     * @return le bloc de règles, déjà borné, ou {@code null} s'il n'y en a aucune
     */
    String rulesFor(UUID userId, UUID workspaceId);
}
