package fr.claudegateway.atelier.agent;

import java.util.List;

/**
 * Résultat d'un run d'atelier ({@code AtelierSessionService.runTask}) (F-28 / Phase 2, ADR-013).
 *
 * @param reply        réponse finale de l'agent
 * @param changedFiles chemins (relatifs au workspace) des fichiers réécrits depuis les sorties
 */
public record AtelierSessionResult(String reply, List<String> changedFiles) {
}
