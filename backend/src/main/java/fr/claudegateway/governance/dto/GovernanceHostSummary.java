package fr.claudegateway.governance.dto;

import java.util.UUID;

import fr.claudegateway.governance.HostMemoryState;

/**
 * Un poste <b>gouvernable</b>, tel que l'écran le liste (F-75 / SF-75-01).
 *
 * @param ref      référence telle qu'elle s'écrit dans une URL — un identifiant, ou {@code hosted}
 * @param id       identifiant du poste réel, <b>nul</b> pour « Hébergé » (décision F-71)
 * @param name     nom lisible
 * @param virtual  vrai pour le poste « Hébergé » — les projets sans machine
 * @param projects nombre de dossiers rangés sous ce poste, ceux qui recevront les fichiers
 * @param active   nombre de paquets actifs sur ce poste
 * @param outdated nombre de ces paquets dont une <b>version plus récente</b> existe (F-96 /
 *                 SF-96-02). Rien ne se met à jour tout seul : ce compte est ce qui permet à
 *                 l'écran de <b>dire qu'une mise à jour attend</b> — sans lui, on ne le verrait que
 *                 sur le poste déjà ouvert, et personne n'irait voir les autres. Calculé en base,
 *                 <b>sans toucher la machine</b>.
 * @param memory   l'état de la <b>mémoire</b> de ce poste (F-135 / SF-135-01) : apprend-il, ou pas ?
 *                 Mesuré le 2026-09-21 : trois postes sur quatre n'apprenaient rien, et rien ne le
 *                 disait. Calculé en base, sans toucher la machine.
 * @param facts    nombre de faits déjà accumulés sur sa carte — zéro tant qu'elle n'a pas été lue
 */
public record GovernanceHostSummary(String ref, UUID id, String name, boolean virtual, int projects,
        int active, int outdated, HostMemoryState memory, int facts) {
}
