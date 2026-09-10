package fr.claudegateway.access;

/**
 * Code d'accès déjà consommé (F-62). Mappée en <b>409</b> ({@code access_code_used}).
 *
 * <p>Distinguer ce refus d'« inconnu » ne livre aucun secret exploitable — un code consommé ne vaut
 * plus rien — et évite de répondre « inconnu » à quelqu'un qui tient un code parfaitement réel.</p>
 */
public class AccessCodeAlreadyUsedException extends RuntimeException {

    public AccessCodeAlreadyUsedException() {
        super("Ce code a déjà été utilisé.");
    }
}
