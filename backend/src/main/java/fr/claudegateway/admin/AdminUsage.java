package fr.claudegateway.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Consommation de la plateforme, <b>par utilisateur</b>, sur une fenêtre (F-61 / SF-61-03).
 *
 * <p><b>Ce qu'il y a dedans</b> : des volumes, des coûts, un plan. <b>Ce qu'il n'y a pas</b> :
 * aucun contenu, et pas davantage de nom de projet ou de poste. Le nom d'une mission appartient au
 * client de l'utilisateur, pas à la plateforme — et un administrateur qui pourrait lire les
 * conversations de ses utilisateurs ferait de la gateway un outil de surveillance.</p>
 *
 * @param currency      devise du coût estimé
 * @param from          premier mois observé (inclus)
 * @param to            dernier mois observé (inclus)
 * @param inputTokens   tokens d'entrée de la plateforme sur la fenêtre
 * @param outputTokens  tokens de sortie de la plateforme sur la fenêtre
 * @param totalTokens   total (entrée + sortie)
 * @param estimatedCost coût estimé total
 * @param users         comptes ayant consommé, du plus consommateur au moins
 */
public record AdminUsage(
        String currency,
        LocalDate from,
        LocalDate to,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        BigDecimal estimatedCost,
        List<UserUsage> users) {

    /**
     * Consommation d'un compte sur la fenêtre.
     *
     * @param share   part du total de la plateforme, entre 0 et 1
     * @param periods évolution mensuelle, du plus ancien au plus récent
     */
    public record UserUsage(
            UUID userId,
            String email,
            String role,
            String planCode,
            String subscriptionStatus,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal estimatedCost,
            BigDecimal share,
            List<MonthUsage> periods) {
    }

    /**
     * Un mois de consommation d'un compte. Entrée et sortie restent séparées jusqu'au bout : leurs
     * coûts unitaires n'ont rien à voir, et une courbe de « tokens » mélangerait deux grandeurs.
     *
     * @param periodStart premier jour du mois (UTC)
     */
    public record MonthUsage(
            LocalDate periodStart,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal estimatedCost) {
    }
}
