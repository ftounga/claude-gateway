package fr.claudegateway.radar;

/**
 * La réponse au manager (F-103 / SF-103-03) n'a pas la forme attendue : pas de marqueur, rien après, ou
 * trop longue. 502 ; rien n'est rendu comme une réponse (cadrage §7 : une sortie illisible n'écrit rien).
 */
public class RadarAnswerUnreadableException extends RuntimeException {

    public RadarAnswerUnreadableException() {
        super("La réponse n'a pas pu être préparée : le fournisseur a rendu une forme illisible. Réessayez.");
    }
}
