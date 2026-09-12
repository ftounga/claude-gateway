package fr.claudegateway.governance.dto;

/**
 * Une section d'un fichier de carte, et <b>ce qu'elle porte</b> (F-92 / SF-92-02).
 *
 * <p>C'est le grain qui rend la carte lisible d'un coup d'œil : on voit non seulement qu'un fichier
 * existe, mais <b>où</b> il est rempli et où il ne l'est pas — « Les pièges : 4 faits, Certificats :
 * aucun ». Un total par fichier ne dirait pas cela.</p>
 *
 * @param title titre de la section, tel qu'il est écrit dans le fichier
 * @param facts nombre de faits qu'elle porte ; 0 pour une section encore vide
 */
public record GovernanceMapSectionView(String title, int facts) {
}
