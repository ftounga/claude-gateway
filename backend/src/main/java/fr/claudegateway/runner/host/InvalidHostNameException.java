package fr.claudegateway.runner.host;

/**
 * Levée lorsqu'un nom de poste est vide (F-48 / SF-48-01). Mappée en 400 : le nom est ce qui rend un
 * poste reconnaissable dans une liste, il ne peut pas être deviné à la place de l'utilisateur.
 */
public class InvalidHostNameException extends RuntimeException {

    public InvalidHostNameException(String message) {
        super(message);
    }
}
