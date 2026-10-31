package fr.claudegateway.atelier.deposit;

/** La pièce désignée n'existe pas dans ce terminal, pour ce compte (F-228 / SF-228-01) → 404. */
public class DepositNotFoundException extends RuntimeException {

    public DepositNotFoundException() {
        super("Pièce introuvable.");
    }
}
