package fr.claudegateway.governance.dto;

import java.util.List;
import java.util.UUID;

/**
 * L'annonce faite <b>avant</b> qu'un paquet n'écrive quoi que ce soit (F-51 / SF-51-03, regrainée
 * par F-75 / SF-75-01).
 *
 * <p>C'est l'exigence explicite de la feature : un paquet écrit sur la machine de l'utilisateur,
 * l'écran dit donc <b>quoi</b> et <b>où</b> avant. Depuis F-75, « où » est au pluriel : on active sur
 * un <b>poste</b>, et les fichiers se posent dans <b>chacun de ses dossiers</b>. L'annonce est donc
 * faite dossier par dossier — une ligne unique laisserait croire à une seule écriture.</p>
 *
 * @param packageId le paquet concerné
 * @param slug      son identifiant lisible
 * @param version   la version qui serait appliquée
 * @param hostRef   le poste visé, tel qu'il s'écrit dans une URL
 * @param hostName  son nom lisible — un identifiant ne dit rien à personne
 * @param files     ce que le paquet apporte : chemin et nature, dans l'ordre du paquet
 * @param projects  ce qui arrivera dans chaque dossier du poste ; vide si le poste n'en porte aucun
 * @param rules     vrai si le paquet ajoute des règles à la consigne système des projets
 * @param controls  nombre de contrôles que le paquet branche sur les crochets de la boucle
 */
public record GovernanceDepositPlan(UUID packageId, String slug, int version, String hostRef,
        String hostName, List<GovernanceFileView> files,
        List<GovernanceProjectDepositPlan> projects, boolean rules, int controls) {
}
