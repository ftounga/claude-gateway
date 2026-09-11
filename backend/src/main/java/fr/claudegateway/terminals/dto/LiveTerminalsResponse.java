package fr.claudegateway.terminals.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * L'état complet du registre des terminaux vivants d'un utilisateur (F-70 / SF-70-01).
 *
 * <p>C'est <b>la même réponse</b> pour une prise de place, un battement de cœur et une simple
 * lecture : un écran qui vient d'obtenir sa place sait immédiatement combien d'autres vivent, et
 * n'a pas de second appel à jouer. Le refus, lui, est un 409 — pas une réponse à `live: 5`.</p>
 *
 * @param limit     plafond en vigueur (4, décision PO — garde-fou de dépense)
 * @param live      nombre de terminaux vivants maintenant
 * @param terminals les terminaux vivants, les plus anciens d'abord
 */
public record LiveTerminalsResponse(int limit, int live, List<LiveTerminal> terminals) {

    /**
     * Un terminal vivant, nommé de façon à ce que l'écran puisse dire <b>lequel fermer</b>.
     *
     * <p>{@code hostName} est résolu depuis les <b>postes de l'utilisateur</b>, jamais depuis le
     * {@code host_id} du projet : c'est la règle posée en SF-49-03 pour qu'un projet pointant vers
     * la machine d'un autre ne puisse pas en révéler le nom. Un poste non résolu rend {@code null}.</p>
     */
    public record LiveTerminal(
            UUID workspaceId,
            String workspaceName,
            UUID hostId,
            String hostName,
            OffsetDateTime openedAt) {
    }
}
