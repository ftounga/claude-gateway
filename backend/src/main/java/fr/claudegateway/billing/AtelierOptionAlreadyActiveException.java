package fr.claudegateway.billing;

/**
 * Souscription refusée : l'option Atelier est déjà en cours. Mappée en <b>409</b>
 * ({@code atelier_option_already_active}) par le {@code GlobalExceptionHandler}.
 */
public class AtelierOptionAlreadyActiveException extends RuntimeException {

    public AtelierOptionAlreadyActiveException() {
        super("L'option Atelier est déjà active sur votre compte.");
    }
}
