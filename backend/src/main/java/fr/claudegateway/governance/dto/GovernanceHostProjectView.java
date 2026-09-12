package fr.claudegateway.governance.dto;

import java.util.UUID;

/**
 * Un dossier rangé sous un poste, vu depuis la gouvernance (F-75 / SF-75-01).
 *
 * <p>Le strict nécessaire pour dire <b>où</b> les fichiers iront : un identifiant, un nom, et le
 * chemin sous la racine du poste. Rien de l'état d'exécution — la Forge le montre déjà.</p>
 *
 * @param id   projet concerné
 * @param name nom lisible
 * @param path chemin sous la racine du poste, ou {@code null} pour la racine / un projet hébergé
 */
public record GovernanceHostProjectView(UUID id, String name, String path) {
}
