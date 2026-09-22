package fr.claudegateway.quota.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * <b>Ce que chaque projet a coûté</b> (F-143 / SF-143-01).
 *
 * <p>Deux montants par projet, et c'est délibéré : la <b>semaine</b> dit ce qui se passe maintenant,
 * le <b>total</b> dit ce que le projet a fini par coûter. L'un sans l'autre ne permet pas
 * d'arbitrer — un projet à 2 € cette semaine peut en avoir coûté 300 depuis mars.</p>
 *
 * @param from    premier jour de la semaine observée
 * @param to      dernier jour de la semaine observée
 * @param projects les projets, les plus coûteux d'abord — c'est l'ordre dans lequel on arbitre
 */
public record ProjectCostView(java.time.LocalDate from, java.time.LocalDate to,
        List<Project> projects) {

    /**
     * Un projet et sa dépense.
     *
     * @param hostName nom du client, ou {@code null} pour un projet sans machine
     * @param weekEur  dépense de la semaine en cours
     * @param totalEur dépense depuis l'origine
     * @param weekTurns tours de la semaine — distingue « n'a rien coûté » de « n'a rien fait »
     */
    public record Project(UUID id, String name, UUID hostId, String hostName,
            BigDecimal weekEur, BigDecimal totalEur, long weekTurns, long totalTurns) {
    }
}
