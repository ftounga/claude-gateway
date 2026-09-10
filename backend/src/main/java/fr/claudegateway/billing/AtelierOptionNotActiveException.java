package fr.claudegateway.billing;

/**
 * Résiliation refusée : aucune option Atelier en cours à résilier. Mappée en <b>409</b>
 * ({@code atelier_option_not_active}) par le {@code GlobalExceptionHandler}.
 */
public class AtelierOptionNotActiveException extends RuntimeException {

    public AtelierOptionNotActiveException() {
        super("Aucune option Forge à résilier.");
    }
}
