package fr.claudegateway.quota;

/**
 * Levée quand un utilisateur a atteint le quota de tokens de sa période courante (F-10). Le proxy
 * refuse l'appel au fournisseur (aucun message persisté). Traduite en {@code 402 Payment Required}
 * par le handler global. Ne transporte aucune donnée sensible.
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
