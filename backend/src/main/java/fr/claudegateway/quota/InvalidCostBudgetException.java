package fr.claudegateway.quota;

/** Budget refusé : négatif, ou au-delà du plafond (F-133 / SF-133-04). Rendu en 400. */
public class InvalidCostBudgetException extends RuntimeException {

    public InvalidCostBudgetException(String message) {
        super(message);
    }
}
