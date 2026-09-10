package fr.claudegateway.access.dto;

import fr.claudegateway.access.AccessCodeService.IssuedAccessCode;

/**
 * Réponse d'émission d'un code (F-62 / SF-62-01) — <b>le seul endroit</b> où le code en clair
 * apparaît, et il n'y réapparaîtra jamais.
 *
 * @param code le code en clair, à remettre au destinataire ; irrécupérable ensuite
 * @param view la description du code, telle qu'elle figurera dans la liste
 */
public record IssuedAccessCodeResponse(String code, AccessCodeAdminView view) {

    /** Projette le résultat d'émission en réponse d'API. */
    public static IssuedAccessCodeResponse from(IssuedAccessCode issued) {
        return new IssuedAccessCodeResponse(issued.code(), AccessCodeAdminView.from(issued.view()));
    }
}
