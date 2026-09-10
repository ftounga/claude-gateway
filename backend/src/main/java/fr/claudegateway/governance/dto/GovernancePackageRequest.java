package fr.claudegateway.governance.dto;

import java.util.List;

/**
 * Le contenu d'un paquet, tel que l'admin le soumet (F-51 / SF-51-01).
 *
 * <p>À la <b>modification</b>, ce corps <b>remplace intégralement</b> le paquet, fichiers compris :
 * une mise à jour partielle laisserait un contenu composite dont personne ne saurait dire de quelle
 * rédaction il vient. Le {@code slug} y est alors ignoré — il est immuable.</p>
 *
 * @param slug       identifiant stable, minuscules / chiffres / tirets ; ignoré à la modification
 * @param name       nom affiché
 * @param summary    une ou deux phrases ; facultatif
 * @param rules      texte ajouté à la consigne système des projets où le paquet est actif
 * @param controlIds identifiants de contrôles du serveur ; tous doivent exister
 * @param files      skills et gabarits déposés dans le projet à l'activation
 */
public record GovernancePackageRequest(String slug, String name, String summary, String rules,
        List<String> controlIds, List<GovernancePackageFileRequest> files) {
}
