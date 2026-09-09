package fr.claudegateway.runner.host;

/**
 * Levée lorsqu'un chemin de projet sous la racine d'un poste est inexploitable (F-48 / SF-48-01).
 * Mappée en 400 : c'est une saisie de l'utilisateur, pas un droit refusé.
 */
public class InvalidProjectPathException extends RuntimeException {

    public InvalidProjectPathException(String message) {
        super(message);
    }
}
