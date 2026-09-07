package fr.claudegateway.billing;

/**
 * L'engagement annuel a été demandé pour un plan qui n'en propose pas (F-43). Traduite en 409.
 *
 * <p>Le repli silencieux vers le price mensuel serait la voie confortable, et il ferait <b>payer au
 * client autre chose que ce qu'il a demandé</b> : il aurait cliqué « à l'année » et serait débité au
 * mois, sans qu'aucun message ne le dise. On refuse explicitement.</p>
 */
public class YearlyBillingUnavailableException extends RuntimeException {

    public YearlyBillingUnavailableException(String message) {
        super(message);
    }
}
