package fr.claudegateway.quota.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.quota.UsageByClient;

/**
 * Réponse de {@code GET /usage/by-client} (F-61 / SF-61-02) : ce que chaque client — chaque
 * <b>poste</b> — a consommé, et chaque projet dessous.
 *
 * <p>Des <b>volumes</b> et des <b>coûts</b>, jamais des contenus : aucun message, aucune commande,
 * aucun chemin. Seuls les <b>noms</b> du poste et du projet accompagnent les chiffres, parce qu'un
 * relevé de refacturation sans libellé ne se lit pas — et l'utilisateur est ici le seul destinataire
 * de ses propres libellés.</p>
 */
public record UsageByClientResponse(
        String currency,
        LocalDate from,
        LocalDate to,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        BigDecimal estimatedCost,
        List<ClientResponse> clients) {

    /** Un client (poste) et ses projets. {@code hostId} nul = seau « hors client ». */
    public record ClientResponse(
            UUID hostId,
            String hostName,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal estimatedCost,
            BigDecimal share,
            List<ProjectResponse> projects) {
    }

    /** Un projet sous son client. {@code workspaceId} nul = tour hors projet. */
    public record ProjectResponse(
            UUID workspaceId,
            String name,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal estimatedCost,
            BigDecimal share) {
    }

    /** Projette l'objet métier en réponse d'API. */
    public static UsageByClientResponse from(UsageByClient usage) {
        List<ClientResponse> clients = usage.clients().stream()
                .map(client -> new ClientResponse(
                        client.hostId(),
                        client.hostName(),
                        client.inputTokens(),
                        client.outputTokens(),
                        client.totalTokens(),
                        client.estimatedCost(),
                        client.share(),
                        client.projects().stream()
                                .map(project -> new ProjectResponse(
                                        project.workspaceId(),
                                        project.name(),
                                        project.inputTokens(),
                                        project.outputTokens(),
                                        project.totalTokens(),
                                        project.estimatedCost(),
                                        project.share()))
                                .toList()))
                .toList();
        return new UsageByClientResponse(
                usage.currency(),
                usage.from(),
                usage.to(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                usage.estimatedCost(),
                clients);
    }
}
