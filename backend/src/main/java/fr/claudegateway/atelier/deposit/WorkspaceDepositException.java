package fr.claudegateway.atelier.deposit;

import org.springframework.http.HttpStatus;

/**
 * Échec <b>nommé</b> d'un dépôt de fichier dans un terminal (F-115 / SF-115-01) : borne dépassée, nom
 * illisible, poste hors ligne, dossier non inscriptible. Porte le statut HTTP et un code de la liste
 * close ; le message est déjà lisible par l'utilisateur (jamais de stacktrace, jamais de chemin
 * absolu de machine). Un dépôt ne finit jamais en silence.
 */
public class WorkspaceDepositException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public WorkspaceDepositException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
