package fr.claudegateway.billing;

/**
 * Levée lorsqu'un changement de plan (upgrade/downgrade, SF-21-05) est demandé alors que
 * l'utilisateur n'a pas d'abonnement payant actif (ex. encore en essai). Mappée en 409 : il faut
 * d'abord souscrire (checkout) avant de pouvoir changer de plan.
 */
public class NoActiveSubscriptionException extends RuntimeException {

    public NoActiveSubscriptionException(String message) {
        super(message);
    }
}
