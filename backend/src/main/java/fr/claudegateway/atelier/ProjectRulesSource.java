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

    /**
     * La <b>phrase de rôle</b> du profil métier actif sur le poste de ce projet, ou {@code null}
     * (F-148 / SF-148-02).
     *
     * <p>Un profil métier (F-138) dit ce qui vaut preuve dans un métier — architecture, infra, sécurité,
     * données. Tant que ses règles rejoignaient la consigne <b>après</b> l'amorce « Tu es un assistant
     * de développement », le mauvais cadre était lu en premier et l'effet du profil était dilué. Cette
     * méthode rend la 1re phrase du profil pour qu'elle <b>remplace</b> l'amorce générique, sans rien
     * desserrer d'autre (garde-fou F-138 : discipline d'investigation et règles de plateforme intactes).</p>
     *
     * <p><b>Méthode {@code default}</b> : l'interface reste fonctionnelle (le SAM est {@link #rulesFor}),
     * et {@link #NONE} comme toute lambda rendent {@code null} — l'amorce générique, comportement d'avant
     * F-148.</p>
     *
     * <p><b>Stable par session</b> : le profil actif ne change pas d'un tour à l'autre. Substituer une
     * phrase stable ne touche pas au cache de prompt (F-134).</p>
     *
     * @param userId      propriétaire du tour (isolation : le profil d'un autre compte ne remonte jamais)
     * @param workspaceId projet du tour
     * @return la phrase de rôle, déjà bornée, ou {@code null} s'il n'y a aucun profil actif
     */
    default String activeProfileRole(UUID userId, UUID workspaceId) {
        return null;
    }
}
