package fr.claudegateway.quota;

/**
 * Fenêtre de consommation refusée (F-61) : bornes inversées, ou période plus longue que le plafond.
 *
 * <p>Exception dédiée plutôt qu'un {@code IllegalArgumentException} nu : le handler global ne doit
 * transformer en {@code 400} que ce qui est <b>réellement</b> une demande invalide. Rendre {@code
 * 400} sur tous les arguments illégaux masquerait en « erreur du client » des défauts qui sont les
 * nôtres.</p>
 */
public class InvalidUsageWindowException extends RuntimeException {

    public InvalidUsageWindowException(String message) {
        super(message);
    }
}
