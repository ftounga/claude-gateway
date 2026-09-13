package fr.claudegateway.billing;

/**
 * Souscription refusée : la Vigie est <b>déjà incluse</b> dans l'offre (Gold Vigie, Gold complet —
 * F-107 / SF-107-03). Mappée en <b>409</b> ({@code vigie_option_included}).
 */
public class VigieOptionIncludedInPlanException extends RuntimeException {

    public VigieOptionIncludedInPlanException() {
        super("La Vigie est déjà incluse dans votre offre.");
    }
}
