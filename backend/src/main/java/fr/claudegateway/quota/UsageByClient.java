package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ce qu'un utilisateur a consommé <b>par client</b> — par <b>poste</b> — et par projet dessous, sur
 * une fenêtre (F-61 / SF-61-02). Objet métier interne, projeté en DTO par le controller.
 *
 * <p>L'usage est la <b>refacturation</b> : savoir quelle mission coûte. D'où deux exigences qui
 * traversent toute la structure — l'entrée et la sortie ne sont jamais confondues (leurs coûts
 * unitaires n'ont rien à voir), et la somme des clients <b>égale</b> le total, seau « hors client »
 * compris.</p>
 *
 * @param currency      devise du coût estimé
 * @param from          premier mois observé (inclus)
 * @param to            dernier mois observé (inclus)
 * @param inputTokens   tokens d'entrée de la fenêtre
 * @param outputTokens  tokens de sortie de la fenêtre
 * @param totalTokens   total (entrée + sortie)
 * @param estimatedCost coût estimé au tarif configuré
 * @param clients       clients, du plus consommateur au moins ; « hors client » toujours en dernier
 */
public record UsageByClient(
        String currency,
        LocalDate from,
        LocalDate to,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        BigDecimal estimatedCost,
        List<ClientUsage> clients) {

    /**
     * Consommation d'un client — c'est-à-dire d'un <b>poste</b> — et de ses projets.
     *
     * @param hostId   identifiant du poste, ou {@code null} pour le seau « hors client »
     * @param hostName nom du poste, ou {@code null} s'il a été supprimé depuis (la dépense reste)
     * @param share    part du total de la fenêtre, entre 0 et 1
     */
    public record ClientUsage(
            UUID hostId,
            String hostName,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal estimatedCost,
            BigDecimal share,
            List<ProjectUsage> projects) {
    }

    /**
     * Consommation d'un projet sous son client.
     *
     * @param workspaceId identifiant du projet, ou {@code null} pour un tour hors projet
     * @param name        nom du projet, ou {@code null} s'il a été supprimé depuis
     * @param share       part du total de la fenêtre, entre 0 et 1
     */
    public record ProjectUsage(
            UUID workspaceId,
            String name,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal estimatedCost,
            BigDecimal share) {
    }
}
