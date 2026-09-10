package fr.claudegateway.governance.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Un paquet tel que l'<b>admin</b> le lit (F-51 / SF-51-01) : le contenu complet, l'état de
 * publication et la version.
 *
 * @param id          identifiant technique
 * @param slug        identifiant lisible et immuable
 * @param name        nom affiché
 * @param summary     une ou deux phrases
 * @param rules       texte injecté dans la consigne système
 * @param controls    contrôles cités, avec {@code known = false} pour un identifiant que le produit
 *                    ne fournit plus
 * @param files       skills et gabarits, contenu compris
 * @param version     version du contenu, incrémentée à chaque modification
 * @param published   visible du catalogue de tous
 * @param publishedAt date de première publication
 * @param updatedAt   dernière modification
 */
public record GovernancePackageAdminView(UUID id, String slug, String name, String summary,
        String rules, List<GovernanceControlView> controls, List<GovernanceFileDetail> files,
        int version, boolean published, OffsetDateTime publishedAt, OffsetDateTime updatedAt) {
}
