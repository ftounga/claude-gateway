package fr.claudegateway.governance.dto;

import java.util.List;

/**
 * Ce qu'un paquet fera à la <b>racine du poste</b> — là où vit la carte (F-92 / SF-92-01).
 *
 * <p>Distincte de {@link GovernanceProjectDepositPlan} parce que l'endroit est distinct : les
 * gabarits et les skills se posent dans <b>chaque</b> projet, la carte se pose <b>une fois</b>, à
 * côté d'eux. Les fondre dans la même liste ferait croire à une carte par projet, c'est-à-dire
 * exactement au contraire de ce que F-92 apporte.</p>
 *
 * <p><b>{@code supported} à faux n'est pas une erreur</b> : le poste « Hébergé » (F-71) n'est pas une
 * machine, il n'a donc pas de racine. Le dépôt ne s'y tente pas, et cette section ne retient jamais
 * une activation en attente.</p>
 *
 * <p><b>{@code readable} à faux non plus</b> : la machine est peut-être éteinte. Chaque entrée est
 * alors {@code UNKNOWN}, <b>rien n'est écrit</b>, et le geste « appliquer » de F-75 reste offert.</p>
 *
 * @param supported faux si ce poste n'a pas de racine (poste « Hébergé »)
 * @param readable  faux si la racine n'a pas pu être lue — machine éteinte, lecture refusée
 * @param message   ce qu'il faut faire quand rien ne peut être déposé, ou {@code null} si tout va bien
 * @param entries   ce qui arrivera à chaque fichier de carte apporté par le paquet
 */
public record GovernanceRootDepositPlan(boolean supported, boolean readable, String message,
        List<GovernanceDepositEntry> entries) {
}
