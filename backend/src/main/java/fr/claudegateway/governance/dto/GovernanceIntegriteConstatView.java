package fr.claudegateway.governance.dto;

import fr.claudegateway.governance.integrite.IntegriteConstat;

/**
 * Un constat d'intégrité, tel que l'écran le lit (F-95 / SF-95-03).
 *
 * <p><b>Le message est rendu tel quel</b>, sans reformulation : il porte déjà son action corrective,
 * et le réécrire côté écran le ferait diverger au premier correctif. C'est la même règle que le
 * relevé de carte (F-92), qui reprend lui aussi les messages du serveur mot pour mot.</p>
 *
 * @param rule    l'identifiant stable de la règle — c'est par lui qu'un constat se retrouve
 * @param target  ce sur quoi il porte : un fichier de carte, un projet, un dépôt
 * @param message le constat <b>et</b> le geste
 */
public record GovernanceIntegriteConstatView(String rule, String target, String message) {

    /** Traduit un constat du domaine en ce que l'écran affiche. */
    public static GovernanceIntegriteConstatView of(IntegriteConstat constat) {
        return new GovernanceIntegriteConstatView(constat.regle().id(), constat.cible(),
                constat.message());
    }
}
