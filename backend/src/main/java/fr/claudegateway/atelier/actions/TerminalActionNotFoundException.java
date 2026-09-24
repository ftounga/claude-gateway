package fr.claudegateway.atelier.actions;

/**
 * Une action introuvable <b>dans le périmètre</b> (F-154 / SF-154-01) — 404.
 *
 * <p>Indiscernable du cas « elle existe mais appartient à un autre » : c'est voulu. Un 403 dirait à
 * un inconnu que l'objet existe.</p>
 */
public class TerminalActionNotFoundException extends RuntimeException {

    public TerminalActionNotFoundException(String message) {
        super(message);
    }
}
