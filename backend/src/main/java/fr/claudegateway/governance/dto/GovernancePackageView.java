package fr.claudegateway.governance.dto;

import java.util.List;
import java.util.UUID;

/**
 * Un paquet du catalogue, tel qu'un <b>utilisateur</b> le lit (F-51 / SF-51-01).
 *
 * <p>Tout ce qui permet de décider en connaissance de cause y est : les règles qui rejoindront la
 * consigne système, les contrôles qui pourront bloquer, et <b>la liste des chemins</b> que le paquet
 * écrira. Le contenu des fichiers, non : il n'est pas nécessaire à la décision.</p>
 *
 * @param id       identifiant technique
 * @param slug     identifiant lisible et stable
 * @param name     nom affiché
 * @param summary  une ou deux phrases
 * @param version  version du contenu publié
 * @param rules    texte qui rejoindra la consigne système ({@code null} si le paquet n'en porte pas)
 * @param controls contrôles activés par ce paquet
 * @param files    ce que le paquet écrira, et où
 */
public record GovernancePackageView(UUID id, String slug, String name, String summary, int version,
        String rules, List<GovernanceControlView> controls, List<GovernanceFileView> files) {
}
