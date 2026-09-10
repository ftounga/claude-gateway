package fr.claudegateway.access;

/**
 * Code d'accès inconnu (F-62). Mappée en <b>404</b> ({@code access_code_invalid}).
 *
 * <p>Le message reste factuel et ne dit rien de plus qu'« inconnu » : c'est le seul refus qui
 * concerne un code n'ayant jamais existé, et il n'y a rien d'utile à en dire de plus.</p>
 */
public class AccessCodeInvalidException extends RuntimeException {

    public AccessCodeInvalidException() {
        super("Ce code d'accès est inconnu. Vérifiez la saisie.");
    }
}
