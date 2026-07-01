package fr.claudegateway.billing.provider;

/**
 * La signature d'un webhook n'a pas pu être vérifiée (payload falsifié, mauvais secret, en-tête
 * absent). Traduite en 400 : aucune mutation n'est appliquée.
 */
public class WebhookVerificationException extends RuntimeException {

    public WebhookVerificationException(String message) {
        super(message);
    }
}
