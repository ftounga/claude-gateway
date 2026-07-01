package fr.claudegateway.billing;

/**
 * Le code de plan demandé n'existe pas dans le catalogue. Traduite en 400.
 */
public class UnknownPlanException extends RuntimeException {

    public UnknownPlanException(String message) {
        super(message);
    }
}
