package fr.claudegateway.billing;

/**
 * Résiliation refusée : aucune option Vigie en cours (F-107 / SF-107-03). Mappée en <b>409</b>
 * ({@code vigie_option_not_active}).
 */
public class VigieOptionNotActiveException extends RuntimeException {

    public VigieOptionNotActiveException() {
        super("Aucune option Vigie à résilier.");
    }
}
