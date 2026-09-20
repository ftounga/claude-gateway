package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ce qu'un <b>poste</b> a coûté sur une fenêtre (F-133 / SF-133-03). Projection de lecture : le
 * journal par tour agrégé par client.
 *
 * <p>{@code costUsd} somme les coûts <b>réels</b> enregistrés. Les tours antérieurs à F-133 n'en ont
 * pas : ils comptent dans les volumes et pour <b>zéro</b> ici. Leur substituer une estimation
 * donnerait un montant crédible et faux — un trou se voit, une approximation non.</p>
 */
public interface HostCostAggregate {

    /** Poste du tour, ou {@code null} pour le seau « hors client ». */
    UUID getHostId();

    /** Somme des coûts réels, en dollars. Jamais {@code null} : zéro quand aucun tour n'en portait. */
    BigDecimal getCostUsd();

    long getInputTokens();

    long getOutputTokens();

    default long totalTokens() {
        return getInputTokens() + getOutputTokens();
    }
}
