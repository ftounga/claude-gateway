package fr.claudegateway.bilan;

/**
 * Un bilan introuvable <b>dans le périmètre</b> (F-155 / SF-155-04) — 404.
 *
 * <p>Indiscernable du cas « il existe mais appartient à un autre » : un 403 dirait à un inconnu que
 * l'objet existe.</p>
 */
public class SessionBilanNotFoundException extends RuntimeException {

    public SessionBilanNotFoundException(String message) {
        super(message);
    }
}
