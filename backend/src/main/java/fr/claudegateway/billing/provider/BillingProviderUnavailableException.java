package fr.claudegateway.billing.provider;

/**
 * Le fournisseur de paiement n'est pas configuré (clé secrète, secret de webhook ou price ID
 * manquant). Traduite en 503 : la fonctionnalité de facturation est momentanément indisponible.
 */
public class BillingProviderUnavailableException extends RuntimeException {

    public BillingProviderUnavailableException(String message) {
        super(message);
    }
}
