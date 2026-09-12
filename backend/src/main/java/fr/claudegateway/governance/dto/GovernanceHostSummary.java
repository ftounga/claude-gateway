package fr.claudegateway.governance.dto;

import java.util.UUID;

/**
 * Un poste <b>gouvernable</b>, tel que l'écran le liste (F-75 / SF-75-01).
 *
 * @param ref      référence telle qu'elle s'écrit dans une URL — un identifiant, ou {@code hosted}
 * @param id       identifiant du poste réel, <b>nul</b> pour « Hébergé » (décision F-71)
 * @param name     nom lisible
 * @param virtual  vrai pour le poste « Hébergé » — les projets sans machine
 * @param projects nombre de dossiers rangés sous ce poste, ceux qui recevront les fichiers
 * @param active   nombre de paquets actifs sur ce poste
 */
public record GovernanceHostSummary(String ref, UUID id, String name, boolean virtual, int projects,
        int active) {
}
