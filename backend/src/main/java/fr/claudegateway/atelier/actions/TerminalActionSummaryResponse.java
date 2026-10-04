package fr.claudegateway.atelier.actions;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * <b>Les compteurs d'attentes du compte</b> (F-175 / SF-175-06) : par poste pour le rail de la Forge,
 * par terminal pour la mosaïque. Seulement les attentes ouvertes ; un poste ou un terminal sans
 * attente n'apparaît pas.
 *
 * @param hosts     un compte par poste ({@code id} = {@code runner_hosts.id})
 * @param terminals un compte par terminal ({@code id} = {@code workspaces.id})
 */
public record TerminalActionSummaryResponse(List<Count> hosts, List<Count> terminals) {

    /**
     * @param aRelancer celles « Demandé » dont la relance est due
     * @param oldestOpenAt la plus ancienne ouverte (postes seulement)
     */
    public record Count(UUID id, int aFaire, int demande, int aRelancer, OffsetDateTime oldestOpenAt) {
    }
}
