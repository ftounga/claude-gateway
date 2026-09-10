package fr.claudegateway.atelier;

import java.util.UUID;

/**
 * Un projet vient d'être créé (F-51 / SF-51-03).
 *
 * <p><b>Pourquoi un événement plutôt qu'un appel direct.</b> Ce qui doit se produire à la création
 * d'un projet — embarquer la sélection de gouvernance marquée « appliquée par défaut » — appartient
 * au module gouvernance, qui dépend déjà de {@link WorkspaceService}. L'appeler directement depuis
 * le service créerait un cycle d'injection que Spring refuserait au démarrage. L'événement rend la
 * dépendance à sens unique : l'Atelier <i>annonce</i>, la gouvernance <i>écoute</i>.</p>
 *
 * <p>Les auditeurs s'exécutent <b>après validation</b> de la transaction et ne doivent jamais faire
 * échouer la création : un projet doit se créer même si la gouvernance a un hoquet.</p>
 *
 * @param userId      créateur du projet (isolation)
 * @param workspaceId projet créé
 */
public record WorkspaceCreatedEvent(UUID userId, UUID workspaceId) {
}
