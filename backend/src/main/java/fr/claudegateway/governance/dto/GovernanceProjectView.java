package fr.claudegateway.governance.dto;

import java.util.List;
import java.util.UUID;

/**
 * L'état de gouvernance d'un projet (F-51 / SF-51-02) : ce qui s'y applique, et ce qui pourrait s'y
 * appliquer.
 *
 * @param workspaceId le projet concerné
 * @param active      les paquets actifs, dans l'ordre où ils ont été activés
 * @param available   les paquets de mon catalogue <b>pas encore</b> actifs ici — la liste dans
 *                    laquelle l'écran propose d'activer
 */
public record GovernanceProjectView(UUID workspaceId, List<GovernanceActivationView> active,
        List<GovernancePackageView> available) {
}
