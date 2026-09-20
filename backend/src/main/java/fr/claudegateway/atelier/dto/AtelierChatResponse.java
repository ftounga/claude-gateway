package fr.claudegateway.atelier.dto;

import java.util.List;
import java.util.UUID;

/**
 * Réponse de {@code POST /api/workspaces/{id}/chat} (F-28 / SF-28-02).
 *
 * <p>Les quatre derniers champs sont <b>additifs</b> (F-39 / SF-39-15) : un client qui les ignore se
 * comporte exactement comme avant.</p>
 *
 * @param reply         réponse finale de Claude
 * @param actions       fichiers lus/écrits par l'agent pendant le tour (pour l'UI)
 * @param messageId     identifiant du message assistant persisté
 * @param inputTokens   tokens d'entrée du tour, cache compris (SF-39-01)
 * @param outputTokens  tokens de sortie du tour
 * @param activeSeconds durée d'horloge du tour, en secondes
 * @param budgetReached le tour s'est arrêté sur le <b>plafond de consommation</b> du message
 * @param costEur       ce que le tour a coûté, formaté en euros (F-133 / SF-133-02), ou
 *                      {@code null} — pour un appelant qui n'est pas administrateur, le montant ne
 *                      quitte pas le serveur
 */
public record AtelierChatResponse(String reply, List<AtelierAction> actions, UUID messageId,
        long inputTokens, long outputTokens, long activeSeconds, boolean budgetReached,
        String costEur) {

    /** Forme sans coût, conservée pour les appelants (et les tests) qui l'attendent. */
    public AtelierChatResponse(String reply, List<AtelierAction> actions, UUID messageId,
            long inputTokens, long outputTokens, long activeSeconds, boolean budgetReached) {
        this(reply, actions, messageId, inputTokens, outputTokens, activeSeconds, budgetReached,
                null);
    }

    /** Action de fichier réalisée par l'agent : {@code type} = {@code read} ou {@code write}. */
    public record AtelierAction(String type, String path) {
    }
}
