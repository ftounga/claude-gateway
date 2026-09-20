package fr.claudegateway.quota.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import fr.claudegateway.quota.CostAlert;

/**
 * Une alerte de dépense telle qu'elle part vers l'écran (F-133 / SF-133-12).
 *
 * <p><b>Les montants ne sortent que pour l'administrateur.</b> Un consultant lit l'alerte et sa
 * part consommée — assez pour agir — mais n'apprend pas ce que la mission coûte à la plateforme.
 * Ils sont <b>absents du JSON</b>, pas masqués à l'écran : masqués, ils resteraient lisibles dans
 * le flux réseau.</p>
 *
 * @param spentEur  dépense de la semaine, ou {@code null} hors administration
 * @param budgetEur budget opposé, ou {@code null} hors administration
 * @param percent   part consommée — <b>toujours visible</b> : elle dit l'ampleur sans dire l'argent
 */
public record CostAlertResponse(
        String scope,
        UUID hostId,
        String hostName,
        BigDecimal spentEur,
        BigDecimal budgetEur,
        int percent,
        String level,
        LocalDate weekStart) {

    /**
     * Projette une alerte pour un appelant donné.
     *
     * @param admin l'appelant est administrateur — décidé dans le thread de la requête
     */
    public static CostAlertResponse from(CostAlert alert, boolean admin) {
        return new CostAlertResponse(
                alert.scope().name(),
                alert.hostId(),
                alert.hostName(),
                admin ? alert.spentEur() : null,
                admin ? alert.budgetEur() : null,
                alert.percent(),
                alert.level().name(),
                alert.weekStart());
    }
}
