package fr.claudegateway.atelier.actions;

import java.util.Locale;

/**
 * <b>La nature d'une attente</b> (F-180 / SF-180-02, décision D1). En prod, les accès faisaient 51 % des
 * attentes et 4,3 j d'attente moyenne quand le reste se fermait le jour même : sans nature, impossible
 * de le voir.
 */
public enum TerminalActionCategorie {

    /** Un compte, un droit, un jeton, un VPN, une ouverture de flux. */
    ACCES,
    /** Un accord, une signature, un « go ». */
    VALIDATION,
    /** Une information qui n'est nulle part ailleurs. */
    INFO,
    /** Un fichier, un document à recevoir ou à transmettre. */
    TRANSMISSION,
    /** Une panne, un ticket, une erreur à faire traiter. */
    INCIDENT;

    /** La valeur lue d'un texte (outil, requête) ; {@code null} si vide ; erreur lisible si inconnue. */
    public static TerminalActionCategorie parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidTerminalActionException(
                    "categorie vaut ACCES, VALIDATION, INFO, TRANSMISSION ou INCIDENT.");
        }
    }
}
