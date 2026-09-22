package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ce qu'un <b>projet</b> a coûté sur une fenêtre (F-143 / SF-143-01) — projection de requête.
 *
 * <p>Le grain qui manquait entre la réponse (SF-133-02) et le client (SF-133-03) : un client porte
 * plusieurs projets, et c'est le projet qu'on ouvre.</p>
 */
public interface ProjectCostAggregate {

    UUID getWorkspaceId();

    /** Somme des coûts réels, en dollars. Jamais {@code null} : zéro quand aucun tour n'en portait. */
    BigDecimal getCostUsd();

    /** Nombre de tours, pour distinguer « rien dépensé » de « rien fait ». */
    long getTurns();
}
