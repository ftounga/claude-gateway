package fr.claudegateway.governance.dto;

import java.util.List;
import java.util.UUID;

/**
 * L'annonce faite <b>avant</b> qu'un paquet n'écrive quoi que ce soit (F-51 / SF-51-03).
 *
 * <p>C'est l'exigence explicite de la feature : un paquet écrit sur la machine de l'utilisateur,
 * l'écran dit donc <b>quoi</b> et <b>où</b> avant. Cette structure est ce que l'écran affiche, et
 * elle est volontairement sans surprise : la liste exacte des chemins, dans l'ordre du paquet.</p>
 *
 * @param packageId le paquet concerné
 * @param slug      son identifiant lisible
 * @param version   la version qui serait appliquée
 * @param readable  faux si le projet n'a pas pu être lu — chaque entrée est alors {@code UNKNOWN}
 * @param entries   ce qui sera écrit, et où ; vide si le paquet n'apporte aucun fichier
 * @param rules     vrai si le paquet ajoute des règles à la consigne système du projet
 * @param controls  nombre de contrôles que le paquet branche sur les crochets de la boucle
 */
public record GovernanceDepositPlan(UUID packageId, String slug, int version, boolean readable,
        List<GovernanceDepositEntry> entries, boolean rules, int controls) {
}
