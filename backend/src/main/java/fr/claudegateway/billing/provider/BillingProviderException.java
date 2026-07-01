package fr.claudegateway.billing.provider;

/**
 * Échec d'appel au fournisseur de paiement (erreur amont). Traduite en 502 par le handler global,
 * sans exposer le détail fournisseur au client.
 */
public class BillingProviderException extends RuntimeException {

    public BillingProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
