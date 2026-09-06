package fr.claudegateway.billing;

/**
 * Souscription refusée : l'Atelier est <b>déjà inclus</b> dans l'offre de l'utilisateur (Gold).
 * Lui vendre l'option serait lui vendre ce qu'il a déjà. Mappée en <b>409</b>
 * ({@code atelier_option_included}) par le {@code GlobalExceptionHandler}.
 */
public class AtelierOptionIncludedInPlanException extends RuntimeException {

    public AtelierOptionIncludedInPlanException() {
        super("L'Atelier est déjà inclus dans votre offre.");
    }
}
