package fr.claudegateway.governance.dto;

import java.util.List;
import java.util.UUID;

/**
 * Ce qu'un paquet fera dans <b>un</b> dossier du poste (F-75 / SF-75-01).
 *
 * <p><b>{@code readable} à faux n'est pas une erreur</b> : la machine est peut-être éteinte. On le
 * dit alors au lieu d'annoncer des créations qu'on n'est pas sûr de faire — chaque entrée est
 * {@code UNKNOWN}, et l'activation reste possible : les règles et les contrôles n'ont besoin d'aucun
 * disque.</p>
 *
 * @param workspaceId le dossier concerné
 * @param name        son nom lisible
 * @param path        son chemin sous la racine du poste, ou {@code null}
 * @param readable    faux si le dossier n'a pas pu être lu
 * @param entries     ce qui arrivera à chaque fichier apporté par le paquet
 */
public record GovernanceProjectDepositPlan(UUID workspaceId, String name, String path,
        boolean readable, List<GovernanceDepositEntry> entries) {
}
