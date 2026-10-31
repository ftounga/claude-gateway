package fr.claudegateway.atelier.deposit;

/** La pièce ne peut pas être lue maintenant — poste hors ligne, fichier disparu ou trop lourd (F-228) → 503. */
public class DepositUnreadableException extends RuntimeException {

    public DepositUnreadableException(String message) {
        super(message);
    }
}
