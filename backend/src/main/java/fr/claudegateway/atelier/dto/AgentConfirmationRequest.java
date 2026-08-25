package fr.claudegateway.atelier.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Bascule de l'option « demander avant d'exécuter » d'un projet (F-33 / SF-33-01).
 *
 * @param enabled vrai pour que l'agent demande l'autorisation avant chaque commande shell.
 *                <b>Obligatoire</b> : un corps sans valeur explicite laisserait deviner l'intention
 *                sur un réglage de sécurité
 */
public record AgentConfirmationRequest(@NotNull Boolean enabled) {
}
