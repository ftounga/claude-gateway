package fr.claudegateway.teams;

import java.util.List;

import fr.claudegateway.teams.TeamsLinkService.TeamsLink;

/**
 * Ce que l'écran reçoit de la liaison Teams (F-87 / SF-87-03).
 *
 * <p>Un <b>état</b>, et de quoi l'écrire. Aucune action n'est exposée : l'indicateur dit où l'on en
 * est, il ne répare rien — un bouton « relancer le navigateur » ne pourrait pas tenir sa promesse,
 * puisque seul l'utilisateur peut lancer son navigateur avec son profil.</p>
 */
public record TeamsLinkResponse(String state, String label, String sentence, String remedy,
        String browser, String healthVerdict, int recognizedFields, int expectedFields,
        List<String> missingFields, List<String> observedApiVersions, boolean conclusive) {

    public static TeamsLinkResponse from(TeamsLink link) {
        return new TeamsLinkResponse(link.state(), link.label(), link.sentence(), link.remedy(),
                link.browser(), link.healthVerdict(), link.recognizedFields(),
                link.expectedFields(), link.missingFields(), link.observedApiVersions(),
                link.conclusive());
    }
}
