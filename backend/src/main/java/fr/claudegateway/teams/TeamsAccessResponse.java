package fr.claudegateway.teams;

/**
 * Le <b>droit Teams</b> du compte, tel que l'écran le lit (F-89 / SF-89-01).
 *
 * <p>Un seul booléen, et c'est délibéré. L'écran a une seule question à poser — <i>le point d'entrée
 * du volet Teams existe-t-il pour ce compte ?</i> — et il ne doit pas avoir à la reconstituer à
 * partir d'un plan, d'un statut d'option et d'un accès offert : ce serait recopier à l'écran une
 * règle de facturation, qui divergerait au premier changement.</p>
 *
 * <p><b>Aucun montant, aucun prix, aucun lien d'achat</b> : le tarif de l'option est à confirmer par
 * le PO. Tant qu'il ne l'est pas, cette réponse dit « oui » ou « non », et rien de plus.</p>
 *
 * @param entitled vrai si le compte peut ouvrir un terminal Teams et si l'agent y recevra les outils
 */
public record TeamsAccessResponse(boolean entitled) {
}
