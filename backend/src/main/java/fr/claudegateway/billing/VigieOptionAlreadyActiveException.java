package fr.claudegateway.billing;

/**
 * Souscription refusée : l'option Vigie est déjà en cours (F-107 / SF-107-03). Mappée en <b>409</b>
 * ({@code vigie_option_already_active}).
 */
public class VigieOptionAlreadyActiveException extends RuntimeException {

    public VigieOptionAlreadyActiveException() {
        super("L'option Vigie est déjà active sur votre compte.");
    }
}
