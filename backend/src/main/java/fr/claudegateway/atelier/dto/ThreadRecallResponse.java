package fr.claudegateway.atelier.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Le résultat d'un **rappel à la demande** (F-165 / SF-165-06) : ce que la commande action
 * {@code /rappel <terme>} affiche, <b>sans aucun tour modèle</b>. Une **recherche** dans l'historique du
 * fil courant (sémantique puis mot-clé), isolée {@code user_id} + propriété du workspace — jamais
 * l'historique d'un autre.
 *
 * <p>Réutilise les briques de recherche du {@code recall} F-162 (l'outil d'agent), exposées ici en
 * lecture : aucun moteur IA, aucun tour.</p>
 *
 * @param query    le terme recherché (normalisé)
 * @param semantic vrai si la recherche a répondu par le <b>sens</b> (embeddings F-162) ; faux si repli
 *                 par <b>mot-clé</b>
 * @param extracts les extraits trouvés, bornés (au plus quelques-uns), du plus pertinent au moins
 */
public record ThreadRecallResponse(String query, boolean semantic, List<Extract> extracts) {

    /**
     * Un extrait d'historique rendu à l'écran. On expose l'extrait <b>de son propre fil</b> — c'est la
     * raison d'être du rappel — mais borné (jamais le message entier).
     *
     * @param role      rôle du message (`user` / `assistant`), pour situer l'extrait
     * @param excerpt   extrait borné du contenu (espaces normalisés, tronqué avec « … »)
     * @param createdAt date du message (ISO)
     */
    public record Extract(String role, String excerpt, OffsetDateTime createdAt) {
    }
}
