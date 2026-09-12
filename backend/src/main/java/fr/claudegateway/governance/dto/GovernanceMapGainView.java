package fr.claudegateway.governance.dto;

import java.time.OffsetDateTime;

/**
 * Un <b>gain</b> constaté sur un fichier de la carte (F-93 / SF-93-02).
 *
 * <p>C'est volontairement pauvre : un fichier, un nombre, une date. « acces.md, +3 faits, le
 * 12 septembre » se constate ; un graphe d'évolution se contemple.</p>
 *
 * @param path    le fichier de carte
 * @param title   son titre, tel que la carte le porte
 * @param gained  le nombre de faits gagnés lors du dernier gain
 * @param gainedAt l'instant où ce gain a été <b>constaté</b> — pas celui où l'écriture a eu lieu, que
 *                 le produit ne peut pas connaître
 */
public record GovernanceMapGainView(String path, String title, int gained,
        OffsetDateTime gainedAt) {
}
