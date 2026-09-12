package fr.claudegateway.governance.dto;

import java.util.List;
import java.util.UUID;

/**
 * L'état de gouvernance d'un <b>poste</b> (F-75 / SF-75-01) : ce qui s'y applique, ce qui pourrait
 * s'y appliquer, et les dossiers que cela concerne.
 *
 * <p>Les <b>projets</b> sont rendus parce que ce sont eux qui recevront les fichiers : l'activation
 * vit sur le poste, les artefacts restent par projet. Un poste sans projet est un état normal — la
 * gouvernance s'y applique déjà, et le premier dossier ajouté demain en héritera.</p>
 *
 * @param ref       référence du poste telle qu'elle s'écrit dans une URL — un identifiant, ou
 *                  {@code hosted}
 * @param id        identifiant du poste réel, <b>nul</b> pour « Hébergé » (décision F-71 : ce poste
 *                  est une vue, il n'a pas d'identifiant)
 * @param name      nom lisible du poste
 * @param virtual   vrai pour le poste « Hébergé »
 * @param projects  les dossiers rangés sous ce poste
 * @param active    les paquets actifs, dans l'ordre où ils ont été activés
 * @param available les paquets de mon catalogue <b>pas encore</b> actifs ici
 */
public record GovernanceHostView(String ref, UUID id, String name, boolean virtual,
        List<GovernanceHostProjectView> projects, List<GovernanceActivationView> active,
        List<GovernancePackageView> available) {
}
