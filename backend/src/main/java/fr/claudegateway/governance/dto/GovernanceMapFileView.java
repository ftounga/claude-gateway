package fr.claudegateway.governance.dto;

import java.util.List;

/**
 * Un fichier de carte, tel que l'écran le montre <b>sans ouvrir un terminal</b> (F-92 / SF-92-02).
 *
 * <p><b>{@code present} et {@code readable} ne disent pas la même chose</b>, et les confondre serait
 * le défaut classique : un fichier absent est un fichier à reposer ; un fichier <i>illisible</i> est
 * une machine à réparer. Une machine éteinte n'est jamais rendue comme une carte vide.</p>
 *
 * @param path      nom du fichier à la racine du poste
 * @param title     son titre de premier niveau, ou son nom à défaut
 * @param present   vrai s'il existe sur la machine
 * @param readable  faux si la machine n'a pas su répondre pour ce fichier
 * @param sections  ses sections, dans l'ordre du fichier, avec le nombre de faits de chacune
 * @param facts     total des faits du fichier ; 0 pour un gabarit livré tel quel
 * @param truncated vrai si le contenu lu a été coupé — une coupe se <b>dit</b>
 * @param message   l'action corrective quand quelque chose manque, {@code null} sinon
 */
public record GovernanceMapFileView(String path, String title, boolean present, boolean readable,
        List<GovernanceMapSectionView> sections, int facts, boolean truncated, String message) {
}
